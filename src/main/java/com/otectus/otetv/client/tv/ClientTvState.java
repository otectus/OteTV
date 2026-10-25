package com.otectus.otetv.client.tv;

import com.otectus.otetv.core.sync.TimelineAnchor;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvDelta;
import com.otectus.otetv.network.TvSnapshot;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * This client's latest knowledge of one TV: an immutable snapshot, replaced atomically (section 9.2 "immutable
 * session snapshots for client readers"). Deltas apply only in revision order within the same epoch.
 */
public final class ClientTvState {
    private final UUID tvId;
    @Nullable
    private volatile TvSnapshot snapshot;
    private volatile long receivedAtNanos;
    private volatile boolean removed;
    private long lastRequestNanos;

    ClientTvState(UUID tvId) {
        this.tvId = tvId;
    }

    public UUID tvId() {
        return tvId;
    }

    @Nullable
    public TvSnapshot snapshot() {
        return snapshot;
    }

    public boolean isRemoved() {
        return removed;
    }

    public long ageMs() {
        return (System.nanoTime() - receivedAtNanos) / 1_000_000L;
    }

    void accept(TvSnapshot s) {
        TvSnapshot current = snapshot;
        if (current != null && current.epoch() == s.epoch() && s.revision() < current.revision()) {
            return; // an older snapshot arrived late
        }
        snapshot = s;
        receivedAtNanos = System.nanoTime();
        removed = false;
    }

    /** Returns false when the delta does not follow the known revision; the caller then requests a snapshot. */
    boolean apply(TvDelta d) {
        TvSnapshot current = snapshot;
        if (current == null || current.epoch() != d.epoch() || current.revision() != d.fromRevision()) {
            return current != null && current.epoch() == d.epoch() && d.revision() <= current.revision();
        }
        snapshot = current.apply(d);
        receivedAtNanos = System.nanoTime();
        return true;
    }

    boolean applyTiming(S2C.Timing t) {
        TvSnapshot current = snapshot;
        if (current == null || current.epoch() != t.epoch() || current.revision() != t.revision()
                || current.sourceGeneration() != t.sourceGeneration()) {
            return false;
        }
        TimelineAnchor timeline = t.timeline();
        snapshot = new TvSnapshot(current.tvId(), current.epoch(), current.revision(), current.sourceGeneration(), current.model(),
                current.displayName(), current.ownerName(), current.yourRole(), current.permissionBits(), current.publicControls(),
                current.powered(), current.channelNumber(), current.channelId(), current.channelLabel(), current.source(), timeline,
                current.speakerGain(), current.preset(), current.redstoneMode(), current.queueSize(), current.queueIndex(),
                current.leaseHolder(), current.leaseGeneration(), current.leaseRemainingMs(), current.leaseIsYours(),
                current.viewerCount(), current.catalogRevision(), current.locked(), current.youtubeAllowed(), current.pos(), current.dimension());
        receivedAtNanos = System.nanoTime();
        return true;
    }

    void markRemoved() {
        removed = true;
    }

    boolean mayRequest(long now) {
        if (now - lastRequestNanos < 750_000_000L) {
            return false;
        }
        lastRequestNanos = now;
        return true;
    }
}
