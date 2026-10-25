package com.otectus.otetv.worker.media;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded presentation queue of decoded frames waiting for their time (section 11.1): obsolete frames are dropped
 * rather than allowed to grow a backlog. Pixel arrays are pooled so steady playback does not churn the heap.
 */
final class FrameQueue {
    static final class Frame {
        int generation;
        long ptsNanos;
        int width;
        int height;
        int length;
        byte[] pixels;
    }

    private volatile int capacity;
    private final Deque<Frame> queue = new ArrayDeque<>();
    private final Deque<byte[]> pool = new ArrayDeque<>();
    private long dropped;

    FrameQueue(int capacity) {
        this.capacity = capacity;
    }

    /** Bounds queued pixels to about 160 MiB whatever the frame size (section 14.3 bounded memory). */
    synchronized void limitForFrameBytes(int frameBytes) {
        capacity = Math.max(12, Math.min(48, (int) (160L * 1024 * 1024 / Math.max(1, frameBytes))));
    }

    synchronized byte[] borrow(int length) {
        while (!pool.isEmpty()) {
            byte[] b = pool.pollFirst();
            if (b.length >= length) {
                return b;
            }
        }
        return new byte[length];
    }

    synchronized void recycle(Frame f) {
        if (f != null && f.pixels != null && pool.size() < capacity + 4) {
            pool.addLast(f.pixels);
        }
    }

    /**
     * Adds a frame, waiting while the queue is full so decoding is back-pressured; after {@code maxWaitMs} the
     * oldest frame is dropped instead, which can never deadlock a badly interleaved file.
     */
    synchronized void push(Frame f, long maxWaitMs) throws InterruptedException {
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (queue.size() >= capacity) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                recycle(queue.pollFirst());
                dropped++;
                break;
            }
            wait(left);
        }
        queue.addLast(f);
        notifyAll();
    }

    synchronized Frame peek() {
        return queue.peekFirst();
    }

    synchronized Frame poll() {
        Frame f = queue.pollFirst();
        notifyAll();
        return f;
    }

    /** The second frame in line, for skipping to the newest frame that is already due. */
    synchronized Frame peekSecond() {
        if (queue.size() < 2) {
            return null;
        }
        var it = queue.iterator();
        it.next();
        return it.next();
    }

    synchronized void clear() {
        while (!queue.isEmpty()) {
            recycle(queue.pollFirst());
        }
        notifyAll();
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized void await(long millis) throws InterruptedException {
        wait(Math.max(1, millis));
    }
}
