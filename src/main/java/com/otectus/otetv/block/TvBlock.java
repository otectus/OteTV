package com.otectus.otetv.block;

import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.client.ClientHooks;
import com.otectus.otetv.item.RemoteControlItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * A television cell. Every cell of an assembly is this block with a {@code part} index; only the anchor part has
 * the controller block entity and renders the baked model. Collision is per cell, so the whole visible footprint is
 * solid (section 5.2).
 */
public class TvBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final EnumProperty<TvMount> MOUNT = EnumProperty.create("mount", TvMount.class);
    private static final IntegerProperty PART_2 = IntegerProperty.create("part", 0, 1);
    private static final IntegerProperty PART_4 = IntegerProperty.create("part", 0, 3);
    private static final IntegerProperty PART_6 = IntegerProperty.create("part", 0, 5);
    /** createBlockStateDefinition runs inside the Block constructor, before subclass fields are assigned. */
    private static final ThreadLocal<TvModel> CONSTRUCTING = new ThreadLocal<>();

    private final TvModel model;
    @Nullable
    private final IntegerProperty partProperty;

    public static TvBlock create(TvModel model, Properties properties) {
        CONSTRUCTING.set(model);
        try {
            return new TvBlock(model, properties);
        } finally {
            CONSTRUCTING.remove();
        }
    }

    protected TvBlock(TvModel model, Properties properties) {
        super(properties);
        this.model = model;
        this.partProperty = partProperty(model);
        BlockState state = this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MOUNT, TvMount.STAND);
        if (partProperty != null) {
            state = state.setValue(partProperty, model.anchorPart());
        }
        registerDefaultState(state);
    }

    @Nullable
    private static IntegerProperty partProperty(TvModel model) {
        return switch (model.cellCount()) {
            case 1 -> null;
            case 2 -> PART_2;
            case 4 -> PART_4;
            case 6 -> PART_6;
            default -> throw new IllegalStateException("unsupported footprint " + model.cellCount());
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        TvModel m = CONSTRUCTING.get();
        if (m == null) {
            throw new IllegalStateException("TvBlock must be created through TvBlock.create");
        }
        builder.add(FACING, MOUNT);
        IntegerProperty p = partProperty(m);
        if (p != null) {
            builder.add(p);
        }
    }

    public TvModel model() {
        return model;
    }

    @Nullable
    public IntegerProperty partProperty() {
        return partProperty;
    }

    public int part(BlockState state) {
        return partProperty == null ? 0 : state.getValue(partProperty);
    }

    public boolean isAnchor(BlockState state) {
        return part(state) == model.anchorPart();
    }

    public BlockState stateFor(Direction facing, TvMount mount, int part) {
        BlockState s = defaultBlockState().setValue(FACING, facing).setValue(MOUNT, model.isWallMountable() ? mount : TvMount.STAND);
        return partProperty == null ? s : s.setValue(partProperty, part);
    }

    // ---- placement ---------------------------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        TvPlacement.Result result = TvPlacement.compute(level, ctx.getPlayer(), this, ctx.getClickedPos(), ctx.getClickedFace(),
                ctx.getHorizontalDirection(), ctx.getItemInHand());
        if (!result.valid()) {
            if (!level.isClientSide && ctx.getPlayer() != null) {
                ctx.getPlayer().displayClientMessage(result.problemMessage(), true);
            }
            return null;
        }
        return stateFor(result.facing(), result.mount(), model.anchorPart());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        Direction facing = state.getValue(FACING);
        TvMount mount = state.getValue(MOUNT);
        for (int part = 0; part < model.cellCount(); part++) {
            if (part == model.anchorPart()) {
                continue;
            }
            level.setBlock(TvGeometry.cellPos(pos, facing, model, part), stateFor(facing, mount, part), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(pos) instanceof TvBlockEntity tv) {
            tv.initializePlacement(placer instanceof ServerPlayer sp ? sp : null, stack);
        }
    }

    // ---- interaction -------------------------------------------------------------------------------------------

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof RemoteControlItem) {
            return InteractionResult.PASS; // the remote's own use handles pairing and switching
        }
        BlockPos anchor = TvAssembly.anchorOf(pos, state);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.useTelevision(anchor, hit.getLocation()));
        } else if (level instanceof ServerLevel serverLevel) {
            TvAssembly.removeIfOrphan(serverLevel, pos, state);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ---- shape and rendering -------------------------------------------------------------------------------------

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return TvGeometry.partShape(model, state.getValue(MOUNT), state.getValue(FACING), part(state));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return isAnchor(state) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            TvAssembly.markCreativeBreak(pos);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            TvAssembly.dismantle(serverLevel, pos, state);
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, neighborPos, moving);
        if (level.isClientSide || TvAssembly.isDismantling()) {
            return;
        }
        // Support and redstone are re-evaluated on the anchor with a short delay: bounded, coalesced work instead of
        // per-tick neighbour scans (section 18.4).
        BlockPos anchor = TvAssembly.anchorOf(pos, state);
        if (level.isLoaded(anchor) && level.getBlockState(anchor).getBlock() == this && !level.getBlockTicks().hasScheduledTick(anchor, this)) {
            level.scheduleTick(anchor, this, 2);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!isAnchor(state)) {
            TvAssembly.removeIfOrphan(level, pos, state);
            return;
        }
        if (TvAssembly.checkSupport(level, pos, state)) {
            return;
        }
        if (TvAssembly.validateAndRepair(level, pos, state) == TvAssembly.Integrity.DISMANTLED) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TvBlockEntity tv) {
            tv.onRedstoneCheck(TvGeometry.cells(pos, state.getValue(FACING), model).stream().anyMatch(level::hasNeighborSignal));
        }
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return !isAnchor(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        TvAssembly.removeIfOrphan(level, pos, state);
    }

    // ---- block entity and signals -------------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isAnchor(state) ? new TvBlockEntity(pos, state) : null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** A coarse power/playback level only; never viewer or account state (section 18.4). */
    @Override
    @SuppressWarnings("deprecation")
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return TvAssembly.controller(level, pos, state).map(TvBlockEntity::comparatorSignal).orElse(0);
    }
}
