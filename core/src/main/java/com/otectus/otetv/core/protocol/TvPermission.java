package com.otectus.otetv.core.protocol;

/** Fine-grained server-side permissions checked on every mutation (section 6.3). */
public enum TvPermission {
    VIEW,
    JOIN,
    POWER,
    PLAYBACK,
    CHANNEL,
    QUEUE_EDIT,
    SOURCE,
    SETTINGS,
    ACL,
    RESET,
    ADMIN
}
