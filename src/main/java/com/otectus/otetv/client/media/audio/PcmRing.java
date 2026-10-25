package com.otectus.otetv.client.media.audio;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded buffer of decoded 48 kHz S16LE stereo PCM with presentation timestamps (section 15.2). The worker can only
 * send audio the client has credit for, so the bound holds without dropping; on overflow (misbehaving worker) the
 * oldest data is discarded and counted as a discontinuity.
 */
public final class PcmRing {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_FRAME = 4;
    public static final long MAX_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 2L; // two seconds

    private static final class Chunk {
        final long ptsNanos;
        final byte[] data;
        int offset;

        Chunk(long ptsNanos, byte[] data) {
            this.ptsNanos = ptsNanos;
            this.data = data;
        }

        long ptsAtOffset() {
            return ptsNanos + (long) (offset / BYTES_PER_FRAME) * 1_000_000_000L / SAMPLE_RATE;
        }
    }

    /** Result of a read: bytes copied and the timestamp of the first copied sample (or -1 when nothing was read). */
    public record Read(int bytes, long ptsNanos) {
    }

    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private long buffered;
    private long consumedTotal;
    private long discontinuities;
    private int generation = Integer.MIN_VALUE;

    public synchronized void reset(int newGeneration) {
        chunks.clear();
        buffered = 0;
        generation = newGeneration;
    }

    public synchronized int generation() {
        return generation;
    }

    public synchronized void push(int chunkGeneration, long ptsNanos, byte[] data) {
        if (chunkGeneration != generation || data.length == 0) {
            return;
        }
        chunks.addLast(new Chunk(ptsNanos, data));
        buffered += data.length;
        while (buffered > MAX_BYTES && !chunks.isEmpty()) {
            Chunk dropped = chunks.removeFirst();
            buffered -= dropped.data.length - dropped.offset;
            consumedTotal += dropped.data.length - dropped.offset;
            discontinuities++;
        }
    }

    /** Copies up to {@code max} bytes (whole frames) into {@code out}. */
    public synchronized Read read(byte[] out, int max) {
        int want = max - (max % BYTES_PER_FRAME);
        int copied = 0;
        long firstPts = -1;
        while (copied < want && !chunks.isEmpty()) {
            Chunk c = chunks.peekFirst();
            if (firstPts < 0) {
                firstPts = c.ptsAtOffset();
            }
            int n = Math.min(want - copied, c.data.length - c.offset);
            System.arraycopy(c.data, c.offset, out, copied, n);
            c.offset += n;
            copied += n;
            if (c.offset >= c.data.length) {
                chunks.removeFirst();
            }
        }
        buffered -= copied;
        consumedTotal += copied;
        return new Read(copied, firstPts);
    }

    /** Discards {@code bytes} of audio, as if played, and returns the timestamp after them. Used by the virtual clock. */
    public synchronized long drain(int bytes) {
        byte[] scratch = new byte[Math.min(bytes, 64 * 1024)];
        int left = bytes;
        long pts = -1;
        while (left > 0 && !chunks.isEmpty()) {
            Read r = read(scratch, Math.min(left, scratch.length));
            if (r.bytes() == 0) {
                break;
            }
            pts = r.ptsNanos() + (long) (r.bytes() / BYTES_PER_FRAME) * 1_000_000_000L / SAMPLE_RATE;
            left -= r.bytes();
        }
        return pts;
    }

    public synchronized long bufferedBytes() {
        return buffered;
    }

    public synchronized long bufferedNanos() {
        return buffered / BYTES_PER_FRAME * 1_000_000_000L / SAMPLE_RATE;
    }

    public synchronized long consumedTotal() {
        return consumedTotal;
    }

    public synchronized long discontinuities() {
        return discontinuities;
    }
}
