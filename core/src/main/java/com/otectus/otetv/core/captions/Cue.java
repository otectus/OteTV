package com.otectus.otetv.core.captions;

/** One caption cue with sanitized plain text (at most three lines). */
public record Cue(long startMs, long endMs, String text) {
    public Cue {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("bad cue timing");
        }
    }

    public boolean activeAt(long positionMs) {
        return positionMs >= startMs && positionMs < endMs;
    }
}
