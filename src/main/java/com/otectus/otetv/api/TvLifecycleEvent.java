package com.otectus.otetv.api;

import com.otectus.otetv.core.protocol.TvActionType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Lifecycle and state events posted on the Forge event bus for integrations (section 20.3). They are notifications:
 * listeners cannot cancel or alter validated decisions, and no private account or browser data is exposed.
 */
public abstract class TvLifecycleEvent extends Event {
    private final UUID tvId;

    protected TvLifecycleEvent(UUID tvId) {
        this.tvId = tvId;
    }

    public UUID tvId() {
        return tvId;
    }

    public static final class Placed extends TvLifecycleEvent {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        @Nullable
        private final UUID owner;

        public Placed(UUID tvId, ResourceKey<Level> dimension, BlockPos pos, @Nullable UUID owner) {
            super(tvId);
            this.dimension = dimension;
            this.pos = pos.immutable();
            this.owner = owner;
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public BlockPos pos() {
            return pos;
        }

        @Nullable
        public UUID owner() {
            return owner;
        }
    }

    public static final class Removed extends TvLifecycleEvent {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;

        public Removed(UUID tvId, ResourceKey<Level> dimension, BlockPos pos) {
            super(tvId);
            this.dimension = dimension;
            this.pos = pos.immutable();
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public BlockPos pos() {
            return pos;
        }
    }

    public static final class StateChanged extends TvLifecycleEvent {
        private final TvActionType action;
        @Nullable
        private final UUID actor;

        public StateChanged(UUID tvId, TvActionType action, @Nullable UUID actor) {
            super(tvId);
            this.action = action;
            this.actor = actor;
        }

        public TvActionType action() {
            return action;
        }

        /** The acting player, or null for redstone and server-side callers. */
        @Nullable
        public UUID actor() {
            return actor;
        }
    }
}
