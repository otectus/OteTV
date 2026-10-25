package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.ipc.Msg;

/**
 * The client's audio clock as reported over IPC: which media timestamp is audible at a given client monotonic time.
 * Client and worker run on the same machine, so their monotonic clocks share a time base; a ping-measured offset
 * corrects any difference. Extrapolates between reports while playing (section 15.2 step 5).
 */
final class ClientClock {
    private volatile long audioPts = -1;
    private volatile long reportClientNanos;
    private volatile long offsetNanos;
    private volatile boolean playing;
    private volatile boolean audioActive;
    private volatile int generation = Integer.MIN_VALUE;

    void report(Msg.ClockReport r) {
        generation = r.generation();
        audioPts = r.audioPtsNanos();
        reportClientNanos = r.clientNanos();
        playing = (r.flags() & Msg.CLOCK_PLAYING) != 0;
        audioActive = (r.flags() & Msg.CLOCK_AUDIO_ACTIVE) != 0;
    }

    void noteOffset(long clientNanos, long workerNanosAtReceive) {
        offsetNanos = workerNanosAtReceive - clientNanos;
    }

    /** The client paused: hold the current estimate instead of extrapolating until its next report says otherwise. */
    void freeze(int currentGeneration) {
        long now = audioPtsNow(currentGeneration);
        if (now >= 0) {
            audioPts = now;
            reportClientNanos = System.nanoTime() - offsetNanos;
        }
        playing = false;
    }

    void reset(int newGeneration) {
        generation = newGeneration;
        audioPts = -1;
    }

    /** Media time audible now, or -1 when the client has no audio clock for this generation. */
    long audioPtsNow(int currentGeneration) {
        long pts = audioPts;
        if (pts < 0 || generation != currentGeneration || !audioActive) {
            return -1;
        }
        if (!playing) {
            return pts;
        }
        long clientNow = System.nanoTime() - offsetNanos;
        return pts + Math.max(0, clientNow - reportClientNanos);
    }
}
