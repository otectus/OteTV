package com.otectus.otetv.client.media.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Feeds decoded PCM to Minecraft's own sound engine as a streaming source, so master and jukebox/record volume, the
 * selected output device, device changes and game pause all behave as for any other sound (section 15.1). Each read
 * returns one fixed-size chunk (100 ms) and records which presentation timestamp it carries; together with the
 * OpenAL queue state this yields the timestamp actually being heard (the audio clock of section 15.2).
 */
public final class TvAudioStream implements AudioStream {
    public static final int CHUNK_MS = 100;
    private static final int CHUNK_FRAMES = PcmRing.SAMPLE_RATE * CHUNK_MS / 1000;

    /** One chunk handed to OpenAL: its first timestamp (or -1 for inserted silence) and length in frames. */
    public record QueuedChunk(long ptsNanos, int frames) {
    }

    private final PcmRing ring;
    private final boolean mono;
    private final AudioFormat format;
    private final byte[] stereo = new byte[CHUNK_FRAMES * PcmRing.BYTES_PER_FRAME];
    private final Deque<QueuedChunk> handed = new ArrayDeque<>();
    private volatile boolean closed;
    private long underruns;

    public TvAudioStream(PcmRing ring, boolean mono) {
        this.ring = ring;
        this.mono = mono;
        this.format = new AudioFormat(PcmRing.SAMPLE_RATE, 16, mono ? 1 : 2, true, false);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /** Called on Minecraft's sound thread whenever OpenAL has room for another buffer. */
    @Override
    public ByteBuffer read(int requested) {
        PcmRing.Read r = ring.read(stereo, stereo.length);
        int frames = CHUNK_FRAMES;
        if (r.bytes() < stereo.length) {
            // Underrun: pad with silence so the source keeps running; the clock records the gap precisely.
            java.util.Arrays.fill(stereo, r.bytes(), stereo.length, (byte) 0);
            if (r.bytes() == 0) {
                underruns++;
            }
        }
        ByteBuffer out = ByteBuffer.allocateDirect(mono ? CHUNK_FRAMES * 2 : stereo.length).order(ByteOrder.LITTLE_ENDIAN);
        if (mono) {
            // OpenAL spatializes mono sources only: downmix for the positional in-world speaker.
            for (int i = 0; i < CHUNK_FRAMES; i++) {
                int base = i * 4;
                int l = (short) ((stereo[base] & 0xFF) | (stereo[base + 1] << 8));
                int rr = (short) ((stereo[base + 2] & 0xFF) | (stereo[base + 3] << 8));
                out.putShort((short) ((l + rr) / 2));
            }
        } else {
            out.put(stereo);
        }
        out.flip();
        synchronized (handed) {
            handed.addLast(new QueuedChunk(r.bytes() > 0 ? r.ptsNanos() : -1, frames));
            while (handed.size() > 64) {
                handed.removeFirst();
            }
        }
        return out;
    }

    /**
     * The timestamp being heard, given how many buffers OpenAL still holds and the sample offset within the oldest.
     * Returns -1 when unknown (for example while silence is playing).
     */
    public long presentedPts(int buffersQueued, int sampleOffset) {
        synchronized (handed) {
            int size = handed.size();
            if (buffersQueued <= 0 || buffersQueued > size) {
                return -1;
            }
            QueuedChunk oldest = handed.stream().skip(size - buffersQueued).findFirst().orElse(null);
            if (oldest == null || oldest.ptsNanos() < 0) {
                return -1;
            }
            int offset = Math.max(0, Math.min(sampleOffset, oldest.frames()));
            return oldest.ptsNanos() + (long) offset * 1_000_000_000L / PcmRing.SAMPLE_RATE;
        }
    }

    /** Fallback estimate when OpenAL state is unavailable: the start of the second-oldest of four queued chunks. */
    public long estimatedPts() {
        synchronized (handed) {
            int size = handed.size();
            if (size < 4) {
                return -1;
            }
            QueuedChunk c = handed.stream().skip(size - 4).findFirst().orElse(null);
            return c == null ? -1 : c.ptsNanos();
        }
    }

    public long underruns() {
        return underruns;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
