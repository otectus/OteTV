package com.otectus.otetv.block;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.entity.TvBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Multi-block integrity rules (section 5.2). One logical TV, one controller block entity on the anchor cell; part
 * cells locate the anchor geometrically. Breaking any part removes the assembly exactly once and drops one item;
 * nothing here force-loads a chunk: parts in unloaded chunks are cleaned up when their chunk loads naturally.
 */
public final class TvAssembly {
    private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
    private static final ThreadLocal<Boolean> DISMANTLING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<BlockPos> CREATIVE_BREAK = new ThreadLocal<>();

    public enum Integrity { INTACT, REPAIRED, DISMANTLED, DEFERRED }

    private TvAssembly() {
    }

    public static boolean isDismantling() {
        return DISMANTLING.get();
    }

    /** Called from {@code playerWillDestroy}: a creative-mode break must not drop the television. */
    public static void markCreativeBreak(BlockPos pos) {
        CREATIVE_BREAK.set(pos.immutable());
    }

    public static BlockPos anchorOf(BlockPos pos, BlockState state) {
        TvBlock block = (TvBlock) state.getBlock();
        return TvGeometry.anchorOf(pos, state.getValue(TvBlock.FACING), block.model(), block.part(state));
    }

    /** Whether {@code cell} holds part {@code part} of the same assembly orientation. */
    public static boolean isMatchingPart(LevelReader level, BlockPos cell, TvBlock block, Direction facing, TvMount mount, int part) {
        BlockState s = level.getBlockState(cell);
        return s.getBlock() == block && s.getValue(TvBlock.FACING) == facing && s.getValue(TvBlock.MOUNT) == mount && block.part(s) == part;
    }

    public static Optional<TvBlockEntity> controller(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof TvBlock)) {
            return Optional.empty();
        }
        BlockPos anchor = anchorOf(pos, state);
        if (!level.isLoaded(anchor)) {
            return Optional.empty();
        }
        BlockEntity be = level.getBlockEntity(anchor);
        return be instanceof TvBlockEntity tv ? Optional.of(tv) : Optional.empty();
    }

    /**
     * Removes the rest of an assembly after {@code removedPos} stopped being part of it. If the removed cell was not
     * the anchor, the anchor drops the television (unless a creative player broke it) and is removed; every other
     * loaded cell is cleared without drops.
     */
    public static void dismantle(ServerLevel level, BlockPos removedPos, BlockState removedState) {
        if (DISMANTLING.get() || level.restoringBlockSnapshots || level.captureBlockSnapshots) {
            return;
        }
        TvBlock block = (TvBlock) removedState.getBlock();
        TvModel model = block.model();
        Direction facing = removedState.getValue(TvBlock.FACING);
        TvMount mount = removedState.getValue(TvBlock.MOUNT);
        int removedPart = block.part(removedState);
        BlockPos anchor = TvGeometry.anchorOf(removedPos, facing, model, removedPart);
        BlockPos creative = CREATIVE_BREAK.get();
        boolean drops = creative == null || !creative.equals(removedPos);
        CREATIVE_BREAK.remove();

        DISMANTLING.set(true);
        try {
            if (removedPart != model.anchorPart() && level.isLoaded(anchor)
                    && isMatchingPart(level, anchor, block, facing, mount, model.anchorPart())) {
                BlockState anchorState = level.getBlockState(anchor);
                BlockEntity be = level.getBlockEntity(anchor);
                if (drops) {
                    Block.dropResources(anchorState, level, anchor, be);
                }
                level.levelEvent(2001, anchor, Block.getId(anchorState));
                level.setBlock(anchor, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            List<BlockPos> cells = TvGeometry.cells(anchor, facing, model);
            for (int part = 0; part < cells.size(); part++) {
                BlockPos cell = cells.get(part);
                // The anchor was handled above, or it is the removed cell itself.
                if (part == model.anchorPart() || cell.equals(removedPos)) {
                    continue;
                }
                if (level.isLoaded(cell) && isMatchingPart(level, cell, block, facing, mount, part)) {
                    level.levelEvent(2001, cell, Block.getId(level.getBlockState(cell)));
                    level.setBlock(cell, Blocks.AIR.defaultBlockState(), FLAGS);
                }
            }
        } finally {
            DISMANTLING.set(false);
        }
    }

    /**
     * A part whose anchor is loaded but no longer a matching controller is an orphan (left behind by a chunk that
     * failed to save, or world editing). Orphans are removed without drops.
     */
    public static boolean removeIfOrphan(ServerLevel level, BlockPos pos, BlockState state) {
        TvBlock block = (TvBlock) state.getBlock();
        if (block.isAnchor(state)) {
            return false;
        }
        BlockPos anchor = anchorOf(pos, state);
        if (!level.isLoaded(anchor)) {
            return false;
        }
        boolean intact = isMatchingPart(level, anchor, block, state.getValue(TvBlock.FACING), state.getValue(TvBlock.MOUNT), block.model().anchorPart())
                && level.getBlockEntity(anchor) instanceof TvBlockEntity;
        if (intact) {
            return false;
        }
        DISMANTLING.set(true);
        try {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        } finally {
            DISMANTLING.set(false);
        }
        OteTV.LOGGER.info("Removed orphaned television part at {}", pos);
        return true;
    }

    /**
     * Controller-side check: repairs missing cells that are loaded and free, dismantles the TV when a cell is
     * occupied by something else, and defers when a cell's chunk is not loaded.
     */
    public static Integrity validateAndRepair(ServerLevel level, BlockPos anchor, BlockState anchorState) {
        TvBlock block = (TvBlock) anchorState.getBlock();
        TvModel model = block.model();
        Direction facing = anchorState.getValue(TvBlock.FACING);
        TvMount mount = anchorState.getValue(TvBlock.MOUNT);
        List<BlockPos> cells = TvGeometry.cells(anchor, facing, model);
        boolean deferred = false;
        boolean repaired = false;
        for (int part = 0; part < cells.size(); part++) {
            if (part == model.anchorPart()) {
                continue;
            }
            BlockPos cell = cells.get(part);
            if (!level.isLoaded(cell)) {
                deferred = true;
                continue;
            }
            if (isMatchingPart(level, cell, block, facing, mount, part)) {
                continue;
            }
            BlockState existing = level.getBlockState(cell);
            if (existing.canBeReplaced() && !(existing.getBlock() instanceof TvBlock)) {
                DISMANTLING.set(true);
                try {
                    level.setBlock(cell, block.stateFor(facing, mount, part), Block.UPDATE_ALL);
                } finally {
                    DISMANTLING.set(false);
                }
                repaired = true;
                OteTV.LOGGER.info("Repaired missing television part at {}", cell);
            } else {
                OteTV.LOGGER.info("Television at {} cannot be repaired: {} occupies {}", anchor, existing, cell);
                level.destroyBlock(anchor, true);
                return Integrity.DISMANTLED;
            }
        }
        if (deferred) {
            return Integrity.DEFERRED;
        }
        return repaired ? Integrity.REPAIRED : Integrity.INTACT;
    }

    /** Dismantles with a drop when support is gone; returns whether it did. */
    public static boolean checkSupport(ServerLevel level, BlockPos anchor, BlockState anchorState) {
        TvBlock block = (TvBlock) anchorState.getBlock();
        if (TvPlacement.hasSupport(level, anchor, anchorState.getValue(TvBlock.FACING), anchorState.getValue(TvBlock.MOUNT), block.model())) {
            return false;
        }
        level.destroyBlock(anchor, true);
        return true;
    }
}
