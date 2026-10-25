package com.otectus.otetv.core.sync;

/**
 * Group-timeline drift correction for direct media (section 10.4), deliberately kept outside the basic audio/video
 * synchronization loop (section 15.2 step 8).
 *
 * <ul>
 *     <li>Under 150 ms: ignore.</li>
 *     <li>150 to 500 ms: bounded rate correction around 1.0, only when the backend supports it cleanly.</li>
 *     <li>Above 500 ms, sustained: seek to the current target, then a cooldown.</li>
 * </ul>
 * Hysteresis stops rate correction only once drift falls well inside the dead band, so corrections do not oscillate.
 * YouTube never uses this controller: its adapter mirrors explicit intent and reports divergence instead.
 */
public final class DriftController {
    public enum Kind { NONE, RATE, SEEK }

    public record Correction(Kind kind, double rate, long seekToMs) {
        static final Correction NONE = new Correction(Kind.NONE, 1.0, -1);
    }

    public static final long IGNORE_BELOW_MS = 150;
    public static final long SEEK_ABOVE_MS = 500;
    public static final long SUSTAIN_MS = 1500;
    public static final long SEEK_COOLDOWN_MS = 5000;
    public static final long HYSTERESIS_MS = 60;
    public static final double MAX_RATE_DEVIATION = 0.03;
    /** Rate correction aims to remove the drift over roughly this window. */
    public static final double CORRECTION_WINDOW_MS = 10_000;

    private long largeDriftSinceMs = -1;
    private long lastSeekAtMs = Long.MIN_VALUE / 2;
    private boolean rateCorrecting;
    private double currentRate = 1.0;

    public Correction update(long nowMs, long observedMs, long targetMs, boolean rateSupported, boolean seekable) {
        long drift = observedMs - targetMs;
        long magnitude = Math.abs(drift);
        boolean inCooldown = nowMs - lastSeekAtMs < SEEK_COOLDOWN_MS;

        if (magnitude > SEEK_ABOVE_MS && seekable) {
            if (largeDriftSinceMs < 0) {
                largeDriftSinceMs = nowMs;
            }
            if (!inCooldown && nowMs - largeDriftSinceMs >= SUSTAIN_MS) {
                largeDriftSinceMs = -1;
                lastSeekAtMs = nowMs;
                rateCorrecting = false;
                currentRate = 1.0;
                return new Correction(Kind.SEEK, 1.0, targetMs);
            }
        } else {
            largeDriftSinceMs = -1;
        }

        if (!rateSupported) {
            return Correction.NONE;
        }
        if (rateCorrecting && magnitude < HYSTERESIS_MS) {
            rateCorrecting = false;
            currentRate = 1.0;
            return new Correction(Kind.RATE, 1.0, -1);
        }
        if (magnitude >= IGNORE_BELOW_MS || (rateCorrecting && magnitude >= HYSTERESIS_MS)) {
            double deviation = -drift / CORRECTION_WINDOW_MS;
            deviation = Math.max(-MAX_RATE_DEVIATION, Math.min(MAX_RATE_DEVIATION, deviation));
            double rate = Math.round((1.0 + deviation) * 1000.0) / 1000.0;
            rateCorrecting = true;
            if (rate != currentRate) {
                currentRate = rate;
                return new Correction(Kind.RATE, rate, -1);
            }
        }
        return Correction.NONE;
    }

    /** Call after any discontinuity (source change, user seek, long buffering) to restart measurement. */
    public void reset() {
        largeDriftSinceMs = -1;
        rateCorrecting = false;
        currentRate = 1.0;
    }

    /** Call when an explicit (user) seek happened so the controller does not immediately seek again. */
    public void noteSeek(long nowMs) {
        lastSeekAtMs = nowMs;
        reset();
    }

    public double currentRate() {
        return currentRate;
    }
}
