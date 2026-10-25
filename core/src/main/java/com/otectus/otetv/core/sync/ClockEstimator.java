package com.otectus.otetv.core.sync;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * NTP-style server clock estimation (section 10.2).
 *
 * <pre>
 * offset_server_minus_client = ((s1 - c0) + (s2 - c3)) / 2
 * round_trip_delay           = (c3 - c0) - (s2 - s1)
 * estimated_server_now       = client_monotonic_now + filtered_offset
 * </pre>
 *
 * Keeps a bounded window, prefers the lowest-delay sample, rejects implausible values and smooths updates. A new
 * server epoch (server restart) clears every estimate. Network asymmetry means the estimate carries an uncertainty of
 * up to half the round trip; callers must not promise frame-exact synchronization.
 */
public final class ClockEstimator {
    public static final int WINDOW = 8;
    public static final long MAX_RTT_NANOS = 3_000_000_000L;
    private static final double SMOOTHING = 0.25;
    private static final long RESYNC_STEP_NANOS = 1_000_000_000L;

    private record Sample(double offsetNanos, long rttNanos) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private long epoch = Long.MIN_VALUE;
    private boolean hasEstimate;
    private double filteredOffset;
    private long bestRtt = Long.MAX_VALUE;

    /** Adds one exchange; returns false if the sample was rejected. */
    public synchronized boolean addSample(long serverEpoch, long c0, long s1, long s2, long c3) {
        if (serverEpoch != epoch) {
            resetInternal();
            epoch = serverEpoch;
        }
        long rtt = (c3 - c0) - (s2 - s1);
        if (c3 < c0 || s2 < s1 || rtt < 0 || rtt > MAX_RTT_NANOS) {
            return false;
        }
        double offset = ((double) (s1 - c0) + (double) (s2 - c3)) / 2.0;
        samples.addLast(new Sample(offset, rtt));
        while (samples.size() > WINDOW) {
            samples.removeFirst();
        }
        Sample best = null;
        for (Sample s : samples) {
            if (best == null || s.rttNanos < best.rttNanos) {
                best = s;
            }
        }
        bestRtt = best.rttNanos;
        if (!hasEstimate) {
            filteredOffset = best.offsetNanos;
            hasEstimate = true;
        } else {
            double delta = best.offsetNanos - filteredOffset;
            if (Math.abs(delta) > RESYNC_STEP_NANOS) {
                // A jump this large means suspension or a clock discontinuity: re-sync from the window.
                filteredOffset = best.offsetNanos;
            } else {
                filteredOffset += delta * SMOOTHING;
            }
        }
        return true;
    }

    public synchronized boolean hasEstimate() {
        return hasEstimate;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized long estimatedServerNow(long clientNowNanos) {
        return clientNowNanos + Math.round(filteredOffset);
    }

    public synchronized long offsetNanos() {
        return Math.round(filteredOffset);
    }

    /** Half of the best round trip: the bound on asymmetry error. */
    public synchronized long uncertaintyNanos() {
        return bestRtt == Long.MAX_VALUE ? -1 : bestRtt / 2;
    }

    public synchronized long bestRttNanos() {
        return bestRtt == Long.MAX_VALUE ? -1 : bestRtt;
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    public synchronized void reset() {
        resetInternal();
        epoch = Long.MIN_VALUE;
    }

    private void resetInternal() {
        samples.clear();
        hasEstimate = false;
        filteredOffset = 0;
        bestRtt = Long.MAX_VALUE;
    }
}
