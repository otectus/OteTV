package com.otectus.otetv.block.entity;

import com.otectus.otetv.api.TvLifecycleEvent;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import com.otectus.otetv.client.ClientHooks;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.registry.ModBlockEntities;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The controller of one assembled television. Owns durable state; the transient session lives in
 * {@link TvSessionService}. Durable state is persisted on real changes and bounded checkpoints, never every tick.
 */
public class TvBlockEntity extends BlockEntity implements Nameable {
    public static final String COSMETIC_TAG = "OteTvCosmetic";

    private TvState state;
    private TvPublicState publicState = TvPublicState.EMPTY;
    private boolean unloading;

    public TvBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.TELEVISION.get(), pos, blockState);
        this.state = new TvState();
        this.state.preset = modelOf(blockState).defaultPreset();
    }

    private static TvModel modelOf(BlockState blockState) {
        return blockState.getBlock() instanceof TvBlock tv ? tv.model() : TvModel.CRT;
    }

    public TvModel model() {
        return modelOf(getBlockState());
    }

    public Direction facing() {
        return getBlockState().getValue(TvBlock.FACING);
    }

    public TvMount mount() {
        return getBlockState().getValue(TvBlock.MOUNT);
    }

    @Nullable
    public UUID tvId() {
        return level != null && level.isClientSide ? publicState.tvId() : state.tvId;
    }

    public TvState state() {
        return state;
    }

    /** Client-side view of the public display state. */
    public TvPublicState publicState() {
        return publicState;
    }

    /**
     * A placement always creates a fresh identity: breaking and replacing a TV, or placing a creative clone, must
     * invalidate old sessions and remotes. Only cosmetic choices carry over from the item (section 9.2).
     */
    public void initializePlacement(@Nullable ServerPlayer placer, ItemStack stack) {
        TvState fresh = new TvState();
        fresh.tvId = UUID.randomUUID();
        fresh.preset = model().defaultPreset();
        if (placer != null) {
            fresh.owner = placer.getUUID();
            fresh.ownerName = placer.getGameProfile().getName();
        }
        if (stack.hasCustomHoverName()) {
            fresh.setDisplayName(stack.getHoverName().getString());
        }
        CompoundTag cosmetic = stack.getTagElement(COSMETIC_TAG);
        if (cosmetic != null) {
            fresh.preset = VisualPreset.byId(cosmetic.getString("preset")).orElse(fresh.preset);
        }
        this.state = fresh;
        setChanged();
        TvSessionService svc = TvSessionService.get();
        if (svc != null) {
            svc.onControllerLoaded(this);
            MinecraftForge.EVENT_BUS.post(new TvLifecycleEvent.Placed(fresh.tvId, level.dimension(), worldPosition, placer == null ? null : placer.getUUID()));
        }
        syncPublicState();
    }

    public void regenerateIdentity() {
        state.tvId = UUID.randomUUID();
        setChanged();
    }

    // ---- persistence ------------------------------------------------------------------------------------------

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("tv")) {
            state = TvState.load(tag.getCompound("tv"), model().defaultPreset());
        }
        if (tag.contains("public")) {
            TvPublicState before = publicState;
            TvPublicState after = TvPublicState.read(tag.getCompound("public"));
            publicState = after;
            if (level != null && level.isClientSide) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onPublicStateChanged(this, before, after));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag tv = new CompoundTag();
        state.save(tv);
        tag.put("tv", tv);
    }

    /** Only public display state goes to watching clients; never the ACL, queue or private data. */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("public", buildPublicState().write());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private TvPublicState buildPublicState() {
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null || state.tvId == null ? null : svc.session(state.tvId);
        String type = state.source == null ? "" : state.source.type().id();
        String label = state.source == null ? "" : state.source.displayLabel();
        String host = state.source == null ? "" : state.source.networkHost().orElse("");
        return new TvPublicState(state.tvId, state.displayName, state.powered, state.ownerName, state.channelNumber, type, label, host,
                session == null ? PlayState.STOPPED : session.timeline().state(), state.preset,
                svc == null ? 0 : svc.epoch(), session == null ? 0 : session.revision(), session == null ? 0 : session.sourceGeneration(),
                state.isLocked());
    }

    /** Records a durable change; {@code publicChanged} also pushes the public display state to watchers. */
    public void markDurableChange(boolean publicChanged) {
        setChanged();
        if (publicChanged) {
            syncPublicState();
        }
    }

    public void syncPublicState() {
        if (level != null && !level.isClientSide) {
            BlockState s = getBlockState();
            level.sendBlockUpdated(worldPosition, s, s, Block.UPDATE_CLIENTS);
            level.updateNeighbourForOutputSignal(worldPosition, s.getBlock());
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            TvSessionService svc = TvSessionService.get();
            if (svc != null) {
                svc.onControllerLoaded(this);
            }
            // Validate the assembly once its neighbours have had a chance to load (repair or clean up).
            serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), 20);
        } else if (level != null && level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onTelevisionLoaded(this));
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unloading = true;
        if (level != null && !level.isClientSide) {
            TvSessionService svc = TvSessionService.get();
            if (svc != null) {
                svc.onControllerUnloaded(this);
            }
        } else if (level != null) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onTelevisionGone(this, false));
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (unloading || level == null) {
            return;
        }
        if (!level.isClientSide) {
            TvSessionService svc = TvSessionService.get();
            if (svc != null) {
                svc.onControllerRemoved(this);
                if (state.tvId != null) {
                    MinecraftForge.EVENT_BUS.post(new TvLifecycleEvent.Removed(state.tvId, level.dimension(), worldPosition));
                }
            }
        } else {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onTelevisionGone(this, true));
        }
    }

    // ---- behaviour -----------------------------------------------------------------------------------------------

    /** Coarse comparator level: 0 off, 5 standby, 10 paused, 15 playing. */
    public int comparatorSignal() {
        if (!state.powered) {
            return 0;
        }
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null || state.tvId == null ? null : svc.session(state.tvId);
        if (session == null || state.source == null) {
            return 5;
        }
        return switch (session.timeline().state()) {
            case PLAYING -> 15;
            case PAUSED -> 10;
            default -> 5;
        };
    }

    public void onRedstoneCheck(boolean high) {
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null || state.tvId == null ? null : svc.session(state.tvId);
        if (session != null) {
            TvActions.redstone(svc, session, this, high);
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        return TvGeometry.assemblyBounds(worldPosition, facing(), model());
    }

    /** Item NBT may never inject ownership, ACLs or sources: only operators may apply block-entity NBT at all. */
    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    /** The cosmetic name, used by the loot table so a dropped TV keeps it. */
    @Override
    public Component getName() {
        return state.displayName.isEmpty() ? Component.translatable(model().translationKey()) : Component.literal(state.displayName);
    }

    @Override
    public boolean hasCustomName() {
        return !state.displayName.isEmpty();
    }

    @Nullable
    @Override
    public Component getCustomName() {
        return hasCustomName() ? Component.literal(state.displayName) : null;
    }

    public static int clampChannel(int number) {
        return Math.max(0, Math.min(Limits.MAX_CHANNEL_NUMBER, number));
    }
}
