package com.otectus.otetv.core.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The client's local playback state machine. Transitions are validated, and every event carries the source
 * generation it belongs to: events from an older generation are ignored, so a late callback from a previous source
 * can never overwrite the current one (section 9.4).
 */
public final class MediaStateMachine {
    private static final Map<LocalPlaybackState, Set<LocalPlaybackState>> ALLOWED = new EnumMap<>(LocalPlaybackState.class);

    static {
        Set<LocalPlaybackState> any = EnumSet.allOf(LocalPlaybackState.class);
        for (LocalPlaybackState s : LocalPlaybackState.values()) {
            ALLOWED.put(s, any);
        }
        // From an idle/not-joined state playback must go through loading first.
        Set<LocalPlaybackState> fromIdle = EnumSet.complementOf(EnumSet.of(LocalPlaybackState.PLAYING, LocalPlaybackState.PAUSED,
                LocalPlaybackState.BUFFERING, LocalPlaybackState.DIVERGED, LocalPlaybackState.ENDED, LocalPlaybackState.LOCALLY_PAUSED));
        ALLOWED.put(LocalPlaybackState.IDLE, fromIdle);
        ALLOWED.put(LocalPlaybackState.NOT_JOINED, fromIdle);
        ALLOWED.put(LocalPlaybackState.NEEDS_CONSENT, fromIdle);
        ALLOWED.put(LocalPlaybackState.RUNTIME_MISSING, fromIdle);
        ALLOWED.put(LocalPlaybackState.FILE_MISSING, fromIdle);
        ALLOWED.put(LocalPlaybackState.UNSUPPORTED, fromIdle);
        ALLOWED.put(LocalPlaybackState.ERROR, fromIdle);
    }

    private LocalPlaybackState state = LocalPlaybackState.IDLE;
    private ErrorCode error = ErrorCode.NONE;
    private String detail = "";
    private int generation;
    private long changedAtNanos;

    public synchronized LocalPlaybackState state() {
        return state;
    }

    public synchronized ErrorCode error() {
        return error;
    }

    public synchronized String detail() {
        return detail;
    }

    public synchronized int generation() {
        return generation;
    }

    public synchronized long changedAtNanos() {
        return changedAtNanos;
    }

    /** Starts a new source generation; everything older becomes stale. */
    public synchronized int beginGeneration(int newGeneration, long nowNanos) {
        generation = newGeneration;
        state = LocalPlaybackState.LOADING;
        error = ErrorCode.NONE;
        detail = "";
        changedAtNanos = nowNanos;
        return generation;
    }

    /** Applies a transition if it belongs to the current generation and is allowed. Returns whether it applied. */
    public synchronized boolean transition(int eventGeneration, LocalPlaybackState next, long nowNanos) {
        if (eventGeneration != generation) {
            return false;
        }
        if (next == state) {
            return true;
        }
        if (!ALLOWED.get(state).contains(next)) {
            return false;
        }
        state = next;
        if (!next.isFailure()) {
            error = ErrorCode.NONE;
            detail = "";
        }
        changedAtNanos = nowNanos;
        return true;
    }

    /** Local-only states (consent, runtime, not joined) apply regardless of generation. */
    public synchronized void force(LocalPlaybackState next, long nowNanos) {
        state = next;
        if (!next.isFailure()) {
            error = ErrorCode.NONE;
            detail = "";
        }
        changedAtNanos = nowNanos;
    }

    public synchronized boolean fail(int eventGeneration, ErrorCode code, String failureDetail, long nowNanos) {
        if (eventGeneration != generation) {
            return false;
        }
        state = code == ErrorCode.UNSUPPORTED_CODEC || code == ErrorCode.UNSUPPORTED_CONTAINER || code == ErrorCode.HLS_ENCRYPTED
                || code == ErrorCode.HLS_UNSUPPORTED ? LocalPlaybackState.UNSUPPORTED
                : code == ErrorCode.RUNTIME_MISSING ? LocalPlaybackState.RUNTIME_MISSING
                : code == ErrorCode.LOCAL_FILE_ABSENT || code == ErrorCode.LOCAL_FILE_MISMATCH ? LocalPlaybackState.FILE_MISSING
                : code == ErrorCode.AUTOPLAY_BLOCKED ? LocalPlaybackState.WAITING_FOR_GESTURE
                : LocalPlaybackState.ERROR;
        error = code;
        detail = failureDetail == null ? "" : failureDetail;
        changedAtNanos = nowNanos;
        return true;
    }
}
