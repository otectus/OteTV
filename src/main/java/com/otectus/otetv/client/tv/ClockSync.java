package com.otectus.otetv.client.tv;

import com.otectus.otetv.core.sync.ClockEstimator;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;

/**
 * Drives NTP-style probes (section 10.2): a short burst when an estimate is first needed or the server epoch
 * changes, then a slow refresh while sessions are active. Estimates are cleared on reconnect and epoch change.
 */
public final class ClockSync {
    private static final long BURST_INTERVAL = 300_000_000L;
    private static final long STEADY_INTERVAL = 10_000_000_000L;

    private final ClockEstimator estimator = new ClockEstimator();
    private long nextProbe;
    private int burst = 5;
    private long knownEpoch = Long.MIN_VALUE;

    public void noteEpoch(long epoch) {
        if (epoch != knownEpoch) {
            knownEpoch = epoch;
            estimator.reset();
            burst = 5;
            nextProbe = 0;
        }
    }

    public void tick(boolean needed) {
        if (!needed && estimator.hasEstimate()) {
            return;
        }
        long now = System.nanoTime();
        if (now >= nextProbe) {
            OteTvNetwork.sendToServer(new C2S.ClockProbe(now));
            nextProbe = now + (burst > 0 ? BURST_INTERVAL : STEADY_INTERVAL);
            if (burst > 0) {
                burst--;
            }
        }
    }

    public void onReply(S2C.ClockReply reply, long c3) {
        if (knownEpoch != Long.MIN_VALUE && reply.epoch() != knownEpoch) {
            noteEpoch(reply.epoch());
        }
        estimator.addSample(reply.epoch(), reply.c0(), reply.s1(), reply.s2(), c3);
    }

    public boolean hasEstimate() {
        return estimator.hasEstimate();
    }

    /** Estimated server-epoch nanoseconds now, or -1 before the first sample. */
    public long serverNow() {
        return estimator.hasEstimate() ? estimator.estimatedServerNow(System.nanoTime()) : -1;
    }

    public long uncertaintyMs() {
        long u = estimator.uncertaintyNanos();
        return u < 0 ? -1 : u / 1_000_000L;
    }

    public long rttMs() {
        long r = estimator.bestRttNanos();
        return r < 0 ? -1 : r / 1_000_000L;
    }

    public void reset() {
        estimator.reset();
        knownEpoch = Long.MIN_VALUE;
        burst = 5;
        nextProbe = 0;
    }
}
