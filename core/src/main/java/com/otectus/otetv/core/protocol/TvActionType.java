package com.otectus.otetv.core.protocol;

/**
 * Typed shared-state mutations (section 9.3). Each declares the permission it needs and whether it is idempotent:
 * an idempotent action carries an explicit desired state and may be applied against a newer revision; anything
 * else based on a stale revision is rejected with the latest state (section 9.4). There is deliberately no network
 * "toggle": clients resolve toggles into explicit desired states before sending.
 */
public enum TvActionType {
    SET_POWER(TvPermission.POWER, true),
    PLAY(TvPermission.PLAYBACK, true),
    PAUSE(TvPermission.PLAYBACK, true),
    STOP(TvPermission.PLAYBACK, true),
    SEEK_ABSOLUTE(TvPermission.PLAYBACK, true),
    SEEK_RELATIVE(TvPermission.PLAYBACK, false),
    SET_SPEAKER_GAIN(TvPermission.PLAYBACK, true),
    SELECT_CHANNEL(TvPermission.CHANNEL, true),
    CHANNEL_STEP(TvPermission.CHANNEL, false),
    QUEUE_NEXT(TvPermission.PLAYBACK, false),
    SET_SOURCE(TvPermission.SOURCE, false),
    CLEAR_SOURCE(TvPermission.SOURCE, true),
    QUEUE_ADD(TvPermission.QUEUE_EDIT, false),
    QUEUE_REMOVE(TvPermission.QUEUE_EDIT, false),
    QUEUE_CLEAR(TvPermission.QUEUE_EDIT, true),
    SET_DISPLAY_NAME(TvPermission.SETTINGS, true),
    SET_VISUAL_PRESET(TvPermission.SETTINGS, true),
    SET_REDSTONE_MODE(TvPermission.SETTINGS, true),
    SET_PUBLIC_CONTROLS(TvPermission.ACL, true),
    TRUST_SET(TvPermission.ACL, true),
    TRUST_REMOVE(TvPermission.ACL, true),
    RESET(TvPermission.RESET, false);

    private final TvPermission permission;
    private final boolean idempotent;

    TvActionType(TvPermission permission, boolean idempotent) {
        this.permission = permission;
        this.idempotent = idempotent;
    }

    public TvPermission permission() {
        return permission;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public static TvActionType byOrdinal(int ordinal) {
        TvActionType[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("unknown action " + ordinal);
        }
        return values[ordinal];
    }
}
