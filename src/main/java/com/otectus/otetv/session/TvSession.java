package com.otectus.otetv.session;

import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.sync.TimelineAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transient authoritative session state for one loaded TV (section 9.1): revision, source generation, control lease,
 * timeline anchor, subscribers and bounded viewer reports. Durable state stays in the block entity.
 */
public final class TvSession {
    public record ViewerReport(int generation, LocalPlaybackState state, long positionMs, long durationMs, long receivedAtNanos) {
    }

    final UUID tvId;
    final ResourceKey<Level> dimension;
    final BlockPos pos;
    final TvModel model;
    long revision = 1;
    int sourceGeneration = 1;
    TimelineAnchor timeline = TimelineAnchor.STOPPED;
    @Nullable
    ControlLease lease;
    int leaseGenerationCounter;
    final Set<UUID> subscribers = new LinkedHashSet<>();
    final Map<UUID, ViewerReport> reports = new HashMap<>();
    final Map<UUID, Long> lastReportNanos = new HashMap<>();
    long lastTimingBroadcast;
    long emptySince = -1;
    long lastCheckpoint;
    long lastIntegrityCheck;
    long lastRedstoneChannelStep = Long.MIN_VALUE / 2;

    TvSession(UUID tvId, ResourceKey<Level> dimension, BlockPos pos, TvModel model) {
        this.tvId = tvId;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.model = model;
    }

    public UUID tvId() {
        return tvId;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return pos;
    }

    public TvModel model() {
        return model;
    }

    public long revision() {
        return revision;
    }

    public int sourceGeneration() {
        return sourceGeneration;
    }

    public TimelineAnchor timeline() {
        return timeline;
    }

    @Nullable
    public ControlLease lease() {
        return lease;
    }

    public Set<UUID> subscribers() {
        return subscribers;
    }

    public Map<UUID, ViewerReport> reports() {
        return reports;
    }

    /** The visual centre of the assembly, used for range checks. */
    public Vec3 center() {
        return Vec3.atCenterOf(pos).add(0, (model.height() - 1) / 2.0, 0);
    }
}
