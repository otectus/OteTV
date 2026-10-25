package com.otectus.otetv.core.sync;

import com.otectus.otetv.core.state.PlayState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncMathTest {
    private static final long MS = 1_000_000L;

    @Test
    void symmetricDelayGivesExactOffset() {
        ClockEstimator e = new ClockEstimator();
        long offset = 5_000 * MS; // server clock is 5s ahead
        long c0 = 100 * MS;
        long s1 = c0 + 20 * MS + offset;
        long s2 = s1 + 1 * MS;
        long c3 = c0 + 41 * MS;
        assertTrue(e.addSample(7, c0, s1, s2, c3));
        assertEquals(offset, e.offsetNanos());
        assertEquals(40 * MS, e.bestRttNanos());
    }

    @Test
    void asymmetryErrorIsBoundedByHalfTheRoundTrip() {
        ClockEstimator e = new ClockEstimator();
        long offset = 2_000 * MS;
        long c0 = 0;
        long s1 = c0 + 10 * MS + offset;   // 10 ms up
        long s2 = s1;
        long c3 = c0 + 10 * MS + 90 * MS;  // 90 ms down
        e.addSample(1, c0, s1, s2, c3);
        long error = Math.abs(e.offsetNanos() - offset);
        assertTrue(error <= e.uncertaintyNanos(), "error " + error + " exceeds uncertainty " + e.uncertaintyNanos());
    }

    @Test
    void prefersLowDelaySamplesAndRejectsImplausibleOnes() {
        ClockEstimator e = new ClockEstimator();
        long offset = 1_000 * MS;
        e.addSample(1, 0, 100 * MS + offset, 100 * MS + offset, 400 * MS);   // slow, biased sample
        e.addSample(1, 1000 * MS, 1005 * MS + offset, 1005 * MS + offset, 1010 * MS); // fast, accurate sample
        assertEquals(10 * MS, e.bestRttNanos());
        assertFalse(e.addSample(1, 2000 * MS, 1000 * MS, 1001 * MS, 1999 * MS), "c3 before c0 must be rejected");
        assertFalse(e.addSample(1, 0, 0, 0, 10_000 * MS), "round trips over the limit must be rejected");
    }

    @Test
    void newServerEpochClearsEstimates() {
        ClockEstimator e = new ClockEstimator();
        e.addSample(1, 0, 500 * MS, 500 * MS, 10 * MS);
        assertTrue(e.hasEstimate());
        e.addSample(2, 0, 9_000 * MS, 9_000 * MS, 10 * MS);
        assertEquals(1, e.sampleCount());
        assertEquals(2, e.epoch());
    }

    @Test
    void smoothingDampsSmallChangesButResyncsLargeJumps() {
        ClockEstimator e = new ClockEstimator();
        e.addSample(1, 0, 100 * MS, 100 * MS, 0);
        long before = e.offsetNanos();
        for (int i = 0; i < 8; i++) {
            e.addSample(1, i * 1000 * MS, i * 1000 * MS + 140 * MS, i * 1000 * MS + 140 * MS, i * 1000 * MS + 1);
        }
        long after = e.offsetNanos();
        assertTrue(after > before && after <= 140 * MS);
    }

    @Test
    void timelineAppliesElapsedOnlyWhilePlayingAndAfterScheduledStart() {
        TimelineAnchor paused = new TimelineAnchor(PlayState.PAUSED, 10_000, 0, 1.0, -1, false);
        assertEquals(10_000, paused.targetPositionMs(60_000 * MS));
        TimelineAnchor playing = new TimelineAnchor(PlayState.PLAYING, 10_000, 1_000 * MS, 1.0, -1, false);
        assertEquals(10_000, playing.targetPositionMs(500 * MS), "scheduled start not reached yet");
        assertEquals(12_000, playing.targetPositionMs(3_000 * MS));
        TimelineAnchor fast = new TimelineAnchor(PlayState.PLAYING, 0, 0, 1.5, -1, false);
        assertEquals(3_000, fast.targetPositionMs(2_000 * MS));
    }

    @Test
    void timelineClampsToKnownDurationExceptLive() {
        TimelineAnchor vod = new TimelineAnchor(PlayState.PLAYING, 59_000, 0, 1.0, 60_000, false);
        assertEquals(60_000, vod.targetPositionMs(10_000 * MS));
        assertTrue(vod.reachedEnd(10_000 * MS));
        TimelineAnchor live = new TimelineAnchor(PlayState.PLAYING, 59_000, 0, 1.0, 60_000, true);
        assertEquals(69_000, live.targetPositionMs(10_000 * MS));
    }

    @Test
    void driftIgnoresSmallErrors() {
        DriftController d = new DriftController();
        assertEquals(DriftController.Kind.NONE, d.update(0, 10_100, 10_000, true, true).kind());
    }

    @Test
    void driftUsesBoundedRateCorrectionInTheMiddleBand() {
        DriftController d = new DriftController();
        DriftController.Correction c = d.update(0, 10_300, 10_000, true, true);
        assertEquals(DriftController.Kind.RATE, c.kind());
        assertTrue(c.rate() < 1.0 && c.rate() >= 1.0 - DriftController.MAX_RATE_DEVIATION);
        DriftController.Correction behind = new DriftController().update(0, 9_000 + 600, 10_000, true, true);
        assertTrue(behind.rate() > 1.0);
    }

    @Test
    void driftWithoutRateSupportDoesNothingInTheMiddleBand() {
        DriftController d = new DriftController();
        assertEquals(DriftController.Kind.NONE, d.update(0, 10_300, 10_000, false, true).kind());
    }

    @Test
    void largeDriftMustBeSustainedBeforeSeekingAndThenCoolsDown() {
        DriftController d = new DriftController();
        assertEquals(DriftController.Kind.NONE, d.update(0, 12_000, 10_000, false, true).kind());
        assertEquals(DriftController.Kind.NONE, d.update(1_000, 13_000, 11_000, false, true).kind());
        DriftController.Correction seek = d.update(1_600, 13_600, 11_600, false, true);
        assertEquals(DriftController.Kind.SEEK, seek.kind());
        assertEquals(11_600, seek.seekToMs());
        // Still wrong immediately afterwards: cooldown prevents a seek storm.
        d.update(2_000, 14_000, 12_000, false, true);
        assertEquals(DriftController.Kind.NONE, d.update(4_000, 16_000, 14_000, false, true).kind());
    }

    @Test
    void unseekableSourcesNeverSeek() {
        DriftController d = new DriftController();
        for (int t = 0; t < 10_000; t += 500) {
            assertTrue(d.update(t, t + 3_000, t, false, false).kind() != DriftController.Kind.SEEK);
        }
    }

    @Test
    void rateCorrectionStopsOnlyInsideHysteresisBand() {
        DriftController d = new DriftController();
        assertEquals(DriftController.Kind.RATE, d.update(0, 10_300, 10_000, true, true).kind());
        // 100 ms is below the 150 ms entry threshold but above the 60 ms exit threshold: keep correcting.
        d.update(500, 10_600, 10_500, true, true);
        assertTrue(d.currentRate() < 1.0);
        DriftController.Correction stop = d.update(1_000, 11_020, 11_000, true, true);
        assertEquals(DriftController.Kind.RATE, stop.kind());
        assertEquals(1.0, stop.rate());
    }
}
