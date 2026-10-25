package com.otectus.otetv.client.media;

import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.PixelFormat;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A bounded three-slot frame exchange between the worker reader thread and the render thread (section 14.1): one
 * slot displayed, one published, one being written. The writer never blocks: with no free slot an incoming frame is
 * skipped. Frames from an older generation are discarded. Native memory is freed explicitly on close.
 */
public final class FrameExchange implements AutoCloseable {
    private static final int SLOTS = 3;

    public static final class Slot {
        ByteBuffer buffer;
        FrameHeader header;

        public ByteBuffer buffer() {
            return buffer;
        }

        public FrameHeader header() {
            return header;
        }
    }

    private final Slot[] slots = new Slot[SLOTS];
    private Slot writing;
    private Slot published;
    private Slot displayed;
    private final byte[] chunk = new byte[64 * 1024];
    private volatile int acceptGeneration = Integer.MIN_VALUE;
    private volatile boolean monochrome;
    private volatile boolean closed;
    private long dropped;
    private long received;

    public FrameExchange() {
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Slot();
        }
    }

    public void acceptGeneration(int generation) {
        this.acceptGeneration = generation;
    }

    public void setMonochrome(boolean value) {
        this.monochrome = value;
    }

    /** Reader thread: copies one frame's pixels into a free slot, or skips them. */
    public void receive(FrameHeader header, IpcReader reader, int pixelBytes) throws IOException {
        Slot slot = null;
        synchronized (this) {
            received++;
            if (!closed && header.generation() == acceptGeneration) {
                for (Slot s : slots) {
                    if (s != published && s != displayed) {
                        slot = s;
                        break;
                    }
                }
                writing = slot;
            }
        }
        if (slot == null) {
            synchronized (this) {
                dropped++;
            }
            reader.skip(pixelBytes);
            return;
        }
        if (slot.buffer == null || slot.buffer.capacity() < pixelBytes) {
            if (slot.buffer != null) {
                MemoryUtil.memFree(slot.buffer);
            }
            slot.buffer = MemoryUtil.memAlloc(pixelBytes);
        }
        ByteBuffer dst = slot.buffer;
        dst.clear();
        int remaining = pixelBytes;
        while (remaining > 0) {
            int n = Math.min(chunk.length, remaining);
            reader.readFully(chunk, 0, n);
            dst.put(chunk, 0, n);
            remaining -= n;
        }
        dst.flip();
        if (monochrome) {
            toMonochrome(dst, header.format());
        }
        slot.header = header;
        synchronized (this) {
            writing = null;
            if (closed) {
                MemoryUtil.memFree(slot.buffer);
                slot.buffer = null;
                return;
            }
            published = slot;
        }
    }

    /** Render thread: takes the newest published frame, if any; the previously displayed slot becomes free. */
    @Nullable
    public synchronized Slot takeLatest() {
        if (published == null || closed) {
            return null;
        }
        if (published.header.generation() != acceptGeneration) {
            published = null;
            return null;
        }
        displayed = published;
        published = null;
        return displayed;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long received() {
        return received;
    }

    /** In-place luminance conversion for the monochrome set's authentic picture (direct media only). */
    private static void toMonochrome(ByteBuffer buf, PixelFormat format) {
        int rIndex = format == PixelFormat.RGBA ? 0 : 2;
        int bIndex = format == PixelFormat.RGBA ? 2 : 0;
        int limit = buf.limit() - 3;
        for (int i = buf.position(); i < limit; i += 4) {
            int r = buf.get(i + rIndex) & 0xFF;
            int g = buf.get(i + 1) & 0xFF;
            int b = buf.get(i + bIndex) & 0xFF;
            byte y = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            buf.put(i, y);
            buf.put(i + 1, y);
            buf.put(i + 2, y);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Slot s : slots) {
            if (s.buffer != null && s != writing) {
                MemoryUtil.memFree(s.buffer);
                s.buffer = null;
            }
        }
        published = null;
        displayed = null;
    }
}
