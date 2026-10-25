package com.otectus.otetv.core.state;

import java.util.Locale;

/**
 * What one client observes locally, independent of shared intent (section 9.1 "private client state"). A client's
 * local state never changes the shared session: one client's buffering or missing file does not pause everyone.
 */
public enum LocalPlaybackState {
    IDLE(false),
    NOT_JOINED(false),
    NEEDS_CONSENT(false),
    RUNTIME_MISSING(true),
    FILE_MISSING(true),
    LOADING(false),
    BUFFERING(false),
    PLAYING(false),
    PAUSED(false),
    LOCALLY_PAUSED(false),
    WAITING_FOR_GESTURE(false),
    ENDED(false),
    DIVERGED(false),
    EXCESS_SESSION(false),
    UNSUPPORTED(true),
    ERROR(true);

    private final boolean failure;

    LocalPlaybackState(boolean failure) {
        this.failure = failure;
    }

    public boolean isFailure() {
        return failure;
    }

    public boolean isActive() {
        return this == LOADING || this == BUFFERING || this == PLAYING || this == PAUSED || this == LOCALLY_PAUSED
                || this == WAITING_FOR_GESTURE || this == DIVERGED;
    }

    public String translationKey() {
        return "otetv.local_state." + name().toLowerCase(Locale.ROOT);
    }

    public static LocalPlaybackState byOrdinal(int ordinal) {
        LocalPlaybackState[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : IDLE;
    }
}
