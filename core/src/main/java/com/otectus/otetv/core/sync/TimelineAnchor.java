package com.otectus.otetv.core.sync;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.state.PlayState;

/**
 * The authoritative shared timeline (section 10.3). Server times are nanoseconds since the server epoch started;
 * an absolute monotonic value is never persisted across restarts.
 *
 * <pre>
 * target_position = anchor_position + max(0, estimated_server_now - anchor_server_time) * rate
 * </pre>
 *
 * Elapsed time only applies while playing; an anchor time in the future is a scheduled start, so the position holds
 * until it arrives. The result is clamped to the known duration.
 */
public record TimelineAnchor(PlayState state, long positionMs, long anchorServerNanos, double rate, long durationMs, boolean live) {
    public static final TimelineAnchor STOPPED = new TimelineAnchor(PlayState.STOPPED, 0, 0, 1.0, -1, false);

    public TimelineAnchor {
        if (!Double.isFinite(rate) || rate <= 0 || rate > 4) {
            throw new IllegalArgumentException("rate");
        }
        if (positionMs < 0 || positionMs > Limits.MAX_POSITION_MS) {
            throw new IllegalArgumentException("position");
        }
    }

    public long targetPositionMs(long estimatedServerNowNanos) {
        if (state != PlayState.PLAYING) {
            return clamp(positionMs);
        }
        long elapsedNanos = Math.max(0, estimatedServerNowNanos - anchorServerNanos);
        double advanced = elapsedNanos / 1_000_000.0 * rate;
        long position = positionMs + (long) Math.min(advanced, (double) Limits.MAX_POSITION_MS);
        return clamp(position);
    }

    public boolean hasStarted(long serverNowNanos) {
        return state == PlayState.PLAYING && serverNowNanos >= anchorServerNanos;
    }

    public boolean reachedEnd(long serverNowNanos) {
        return durationMs > 0 && !live && targetPositionMs(serverNowNanos) >= durationMs;
    }

    private long clamp(long position) {
        if (position < 0) {
            return 0;
        }
        if (durationMs > 0 && !live && position > durationMs) {
            return durationMs;
        }
        return Math.min(position, Limits.MAX_POSITION_MS);
    }

    public TimelineAnchor withState(PlayState newState, long newPositionMs, long newAnchorNanos) {
        return new TimelineAnchor(newState, Math.max(0, Math.min(newPositionMs, Limits.MAX_POSITION_MS)), newAnchorNanos, rate, durationMs, live);
    }

    public TimelineAnchor withDuration(long newDurationMs, boolean isLive) {
        return new TimelineAnchor(state, positionMs, anchorServerNanos, rate, newDurationMs, isLive);
    }
}
