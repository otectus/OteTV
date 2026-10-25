package com.otectus.otetv.client.media.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * Owns one session's audio path: PCM ring, Minecraft streaming instance and the local media clock (section 15.2).
 * <ol>
 *     <li>Decoded PCM keeps its presentation timestamps.</li>
 *     <li>Samples handed to OpenAL and its queue position are tracked, so the clock follows what is actually heard.</li>
 *     <li>When no audible output is possible (category muted, no device), a virtual drain consumes PCM in real time
 *     so the clock and decoding continue and no PCM piles up.</li>
 *     <li>Seek, pause, flush, device reset and generation change reset the queues atomically.</li>
 * </ol>
 */
public final class TvAudioOutput implements AutoCloseable {
    private static final long PREBUFFER_NANOS = 350_000_000L;

    private final PcmRing ring = new PcmRing();
    private final Supplier<Float> volume;
    private final Supplier<Vec3> position;
    private TvSoundInstance instance;
    private TvAudioStream stream;
    private boolean stereo;
    private int range = 16;
    private boolean running;
    private boolean endOfStream;
    // Virtual clock state, used while no OpenAL source consumes the ring.
    private long virtualStartNanos = -1;
    private long virtualStartPts = -1;
    private long virtualDrained;
    private long lastPts = -1;
    private long lastPtsAtNanos;
    private long instanceStartedNanos;
    private long retryAfterNanos;

    public TvAudioOutput(Supplier<Float> volume, Supplier<Vec3> position) {
        this.volume = volume;
        this.position = position;
    }

    public PcmRing ring() {
        return ring;
    }

    /** Clears everything for a new flush generation (open, seek, resume). */
    public void reset(int generation) {
        stopInstance();
        ring.reset(generation);
        running = false;
        endOfStream = false;
        virtualStartNanos = -1;
        virtualDrained = 0;
        lastPts = -1;
    }

    public void setRunning(boolean value) {
        if (!value) {
            stopInstance();
            virtualStartNanos = -1;
        }
        running = value;
    }

    public void setEndOfStream() {
        endOfStream = true;
    }

    /** True once end of stream was signalled and every real sample has been played out, audibly or virtually. */
    public boolean tailPlayed() {
        if (!endOfStream || ring.bufferedBytes() > 0) {
            return false;
        }
        if (instance == null || stream == null) {
            return true;
        }
        int[] q = AudioChannels.queueState(instance);
        return !stream.realAudioQueued(q == null ? 4 : q[0]);
    }

    public void configure(boolean stereoRelative, int audibleRange) {
        if (stereo != stereoRelative || range != audibleRange) {
            stereo = stereoRelative;
            range = audibleRange;
            if (instance != null) {
                // Changing between positional mono and relative stereo needs a new source; resync from the clock.
                stopInstance();
            }
        }
    }

    /** Client tick: start or restart audible output, or drive the virtual clock when nothing can be heard. */
    public void tick() {
        if (!running) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (instance != null) {
            if (mc.getSoundManager().isActive(instance)) {
                virtualStartNanos = -1;
                return;
            }
            // Stopped by the engine (category or master volume at zero, no free streaming channel, device reset).
            if (now - instanceStartedNanos < 500_000_000L) {
                return;
            }
            stopInstance();
            retryAfterNanos = now + 1_000_000_000L;
        }
        boolean canBeHeard = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) > 0
                && mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS) > 0;
        boolean enoughData = ring.bufferedNanos() >= PREBUFFER_NANOS || (endOfStream && ring.bufferedBytes() > 0);
        if (canBeHeard && enoughData && now >= retryAfterNanos) {
            stream = new TvAudioStream(ring, !stereo);
            instance = new TvSoundInstance(stream, stereo, range, volume, position);
            mc.getSoundManager().play(instance);
            instanceStartedNanos = now;
            virtualStartNanos = -1;
            return;
        }
        // Prebuffering waits; the virtual clock runs only when nothing can be heard or audible output just failed.
        if (!canBeHeard || now < retryAfterNanos || ring.bufferedNanos() > PREBUFFER_NANOS * 3) {
            driveVirtual();
        }
    }

    private void driveVirtual() {
        long now = System.nanoTime();
        if (virtualStartNanos < 0) {
            if (ring.bufferedBytes() == 0) {
                return;
            }
            virtualStartNanos = now;
            virtualDrained = 0;
            virtualStartPts = -1;
        }
        long due = (now - virtualStartNanos) * PcmRing.SAMPLE_RATE / 1_000_000_000L * PcmRing.BYTES_PER_FRAME;
        long toDrain = due - virtualDrained;
        if (toDrain >= PcmRing.BYTES_PER_FRAME) {
            long pts = ring.drain((int) Math.min(toDrain, Integer.MAX_VALUE));
            virtualDrained += toDrain;
            if (pts >= 0) {
                lastPts = pts;
                lastPtsAtNanos = now;
            }
        }
    }

    /** Timestamp currently being heard (nanoseconds of media time), extrapolated between measurements; -1 if unknown. */
    public long presentedPtsNanos() {
        long now = System.nanoTime();
        if (instance != null && stream != null) {
            int[] q = AudioChannels.queueState(instance);
            long pts = q == null ? stream.estimatedPts() : stream.presentedPts(q[0], q[1]);
            if (pts >= 0) {
                lastPts = pts;
                lastPtsAtNanos = now;
                return pts;
            }
        }
        // After the last sample the position holds instead of drifting past the end.
        if (lastPts >= 0 && running && !(endOfStream && ring.bufferedBytes() == 0)) {
            return lastPts + (now - lastPtsAtNanos);
        }
        return lastPts;
    }

    public boolean isAudible() {
        return instance != null && Minecraft.getInstance().getSoundManager().isActive(instance);
    }

    public long consumedBytes() {
        return ring.consumedTotal();
    }

    private void stopInstance() {
        if (instance != null) {
            Minecraft.getInstance().getSoundManager().stop(instance);
            AudioChannels.forget(instance);
            instance = null;
        }
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    @Override
    public void close() {
        running = false;
        stopInstance();
        ring.reset(Integer.MIN_VALUE);
    }
}
