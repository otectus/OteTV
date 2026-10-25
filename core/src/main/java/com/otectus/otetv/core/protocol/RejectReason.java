package com.otectus.otetv.core.protocol;

import java.util.Locale;

/** Why the server refused an action or request; each maps to an {@code otetv.reject.*} translation key. */
public enum RejectReason {
    NONE,
    NOT_FOUND,
    NOT_LOADED,
    WRONG_DIMENSION,
    WRONG_WORLD,
    OUT_OF_RANGE,
    NO_PERMISSION,
    LEASE_HELD,
    STALE_REVISION,
    RATE_LIMITED,
    INVALID_ARGUMENT,
    SOURCE_REJECTED,
    SOURCE_NOT_ALLOWED,
    YOUTUBE_DISABLED,
    CHANNEL_MISSING,
    CHANNEL_DISABLED,
    NO_CHANNELS,
    NOT_POWERED,
    NO_SOURCE,
    NOT_SEEKABLE,
    QUEUE_FULL,
    QUEUE_EMPTY,
    LIMIT_REACHED,
    INCOMPATIBLE_DATA,
    PLAYER_UNKNOWN,
    TOO_FAST,
    INTERNAL;

    public String translationKey() {
        return "otetv.reject." + name().toLowerCase(Locale.ROOT);
    }

    public static RejectReason byOrdinal(int ordinal) {
        RejectReason[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : INTERNAL;
    }
}
