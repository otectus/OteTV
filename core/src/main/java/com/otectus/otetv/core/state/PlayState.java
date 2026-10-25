package com.otectus.otetv.core.state;

/** Shared, server-authoritative playback intent. */
public enum PlayState {
    STOPPED,
    PAUSED,
    PLAYING;

    public static PlayState byOrdinal(int ordinal) {
        PlayState[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : STOPPED;
    }
}
