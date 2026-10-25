package com.otectus.otetv.block;

import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.data.OteTvWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Validates a whole assembly before anything is placed (section 5.2): build height, loaded chunks, replaceability,
 * entity obstruction, spawn protection and world border, support, and the per-player limit. Placement then commits
 * every cell in the same tick, so it is effectively atomic. The client runs the same computation for the outline.
 */
public final class TvPlacement {
    public record Result(BlockPos anchor, Direction facing, TvMount mount, List<BlockPos> cells, String problem) {
        public boolean valid() {
            return problem == null;
        }

        public Component problemMessage() {
            return Component.translatable("otetv.placement." + problem);
        }
    }

    private TvPlacement() {
    }

    /**
     * @param clickedPos the position a normal block would occupy (already adjusted for replaceable blocks)
     * @param clickedFace the face of the block the player clicked
     */
    public static Result compute(Level level, @Nullable Player player, TvBlock block, BlockPos clickedPos, Direction clickedFace,
                                 Direction playerFacing, ItemStack stack) {
        TvModel model = block.model();
        TvMount mount = TvMount.STAND;
        Direction facing = playerFacing.getOpposite();
        if (model.isWallMountable() && clickedFace.getAxis().isHorizontal()) {
            mount = TvMount.WALL;
            facing = clickedFace;
        }
        BlockPos anchor = clickedPos;
        List<BlockPos> cells = TvGeometry.cells(anchor, facing, model);
        String problem = validate(level, player, block, anchor, facing, mount, cells, clickedFace, stack);
        return new Result(anchor, facing, mount, cells, problem);
    }

    private static String validate(Level level, @Nullable Player player, TvBlock block, BlockPos anchor, Direction facing, TvMount mount,
                                   List<BlockPos> cells, Direction clickedFace, ItemStack stack) {
        TvModel model = block.model();
        for (int part = 0; part < cells.size(); part++) {
            BlockPos cell = cells.get(part);
            if (level.isOutsideBuildHeight(cell)) {
                return "outside_world";
            }
            if (!level.isLoaded(cell)) {
                return "unloaded";
            }
            if (!level.getWorldBorder().isWithinBounds(cell)) {
                return "world_border";
            }
            BlockState existing = level.getBlockState(cell);
            if (!existing.canBeReplaced()) {
                return "obstructed";
            }
            if (player != null && (!level.mayInteract(player, cell) || !player.mayUseItemAt(cell, clickedFace, stack))) {
                return "no_permission";
            }
            BlockState partState = block.stateFor(facing, mount, part);
            CollisionContext ctx = player == null ? CollisionContext.empty() : CollisionContext.of(player);
            if (!level.isUnobstructed(partState, cell, ctx)) {
                return "entity_in_way";
            }
        }
        if (!hasSupport(level, anchor, facing, mount, model)) {
            return mount == TvMount.WALL ? "needs_wall" : "needs_floor";
        }
        if (player != null && level instanceof ServerLevel serverLevel && !player.hasPermissions(2)) {
            int limit = OteTvServerConfig.MAX_TVS_PER_PLAYER.get();
            if (OteTvWorldData.get(serverLevel.getServer()).countOwnedBy(player.getUUID()) >= limit) {
                return "limit";
            }
        }
        return null;
    }

    /**
     * Stand mode needs center support under at least one bottom-row cell; wall mode needs a sturdy face behind every
     * cell. Support loss later dismantles the assembly exactly once.
     */
    public static boolean hasSupport(Level level, BlockPos anchor, Direction facing, TvMount mount, TvModel model) {
        if (mount == TvMount.WALL) {
            for (BlockPos cell : TvGeometry.cells(anchor, facing, model)) {
                BlockPos behind = cell.relative(facing.getOpposite());
                if (!level.isLoaded(behind)) {
                    return true; // never judge support across an unloaded chunk
                }
                if (!level.getBlockState(behind).isFaceSturdy(level, behind, facing)) {
                    return false;
                }
            }
            return true;
        }
        for (int column = -model.anchorColumn(); column < model.width() - model.anchorColumn(); column++) {
            BlockPos below = TvGeometry.cellPos(anchor, facing, column, 0).below();
            if (!level.isLoaded(below)) {
                return true;
            }
            if (Block.canSupportCenter(level, below, Direction.UP)) {
                return true;
            }
        }
        return false;
    }
}
