package com.otectus.otetv.client.media;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.client.media.audio.TvAudioOutput;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.ipc.PayloadWriter;
import com.otectus.otetv.core.ipc.SourceGrant;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.text.TextSanitizer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Direct-media playback through the out-of-process GStreamer worker (section 11). The worker decodes and paces;
 * PCM arrives with timestamps and is played through Minecraft's sound engine; frames are released by the worker
 * against this client's audio clock, so lip sync follows what is actually heard (section 15.2).
 */
public final class DirectMediaBackend implements PlaybackBackend, WorkerProcess.Listener {
    private static final int MAX_CUES = 10_000;

    private final WorkerProcess worker;
    private final FrameExchange frames = new FrameExchange();
    private final TvAudioOutput audio;
    private final List<Cue> cues = new CopyOnWriteArrayList<>();
    private volatile int generation;
    private volatile LocalPlaybackState state = LocalPlaybackState.LOADING;
    private volatile ErrorCode error = ErrorCode.NONE;
    private volatile String detail = "";
    private volatile long positionMs;
    private volatile long durationMs = -1;
    private volatile boolean live;
    private volatile int flags;
    private volatile int buffering;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile boolean ended;
    private volatile PlaybackCapabilities capabilities = PlaybackCapabilities.NONE;
    private volatile String runtimeVersion = "";
    private volatile boolean rateControl;
    private volatile long lastFramePtsNanos = -1;
    private boolean playing;
    private long lastCreditSent = -1;
    private int lastCreditGeneration = Integer.MIN_VALUE;

    private DirectMediaBackend(RuntimeManager.LaunchSpec spec, Supplier<Float> volume, Supplier<Vec3> position) throws IOException {
        // Every field the reader thread touches is initialized before the worker starts.
        this.audio = new TvAudioOutput(volume, position);
        this.worker = WorkerProcess.launch("media worker", spec.command(), spec.environment(), spec.workDir(), this, true);
    }

    public static DirectMediaBackend start(RuntimeManager.LaunchSpec spec, SourceGrant grant, Supplier<Float> volume, Supplier<Vec3> position) throws IOException {
        DirectMediaBackend backend = new DirectMediaBackend(spec, volume, position);
        backend.worker.send(IpcType.HELLO, new Msg.Hello(OteTvCore.IPC_PROTOCOL_VERSION, OteTV.MOD_ID, Msg.ROLE_MEDIA,
                com.otectus.otetv.config.OteTvClientConfig.SAFE_MODE.get()).write());
        backend.open(grant);
        return backend;
    }

    private void open(SourceGrant grant) {
        generation = grant.generation();
        frames.acceptGeneration(generation);
        audio.reset(generation);
        cues.clear();
        ended = false;
        playing = !grant.startPaused();
        audio.setRunning(playing);
        PayloadWriter w = new PayloadWriter(256);
        grant.write(w);
        worker.send(IpcType.OPEN, w);
    }

    public void setMonochrome(boolean value) {
        frames.setMonochrome(value);
    }

    public void configureAudio(boolean stereoRelative, int range) {
        audio.configure(stereoRelative, range);
    }

    @Override
    public Kind kind() {
        return Kind.DIRECT_MEDIA;
    }

    @Override
    public void play() {
        playing = true;
        ended = false;
        audio.setRunning(true);
        worker.send(IpcType.PLAY, new Msg.Gen(generation).write());
    }

    @Override
    public void pause() {
        playing = false;
        audio.setRunning(false);
        worker.send(IpcType.PAUSE, new Msg.Gen(generation).write());
    }

    @Override
    public void seek(long position, int newGeneration) {
        generation = newGeneration;
        frames.acceptGeneration(newGeneration);
        audio.reset(newGeneration);
        audio.setRunning(playing);
        ended = false;
        positionMs = position;
        lastFramePtsNanos = -1;
        worker.send(IpcType.SEEK, new Msg.Seek(newGeneration, position, true).write());
    }

    @Override
    public void setRate(double rate) {
        if (rateControl) {
            worker.send(IpcType.SET_RATE, new Msg.SetRate(generation, rate).write());
        }
    }

    @Override
    public void setVideoEnabled(boolean enabled) {
        worker.send(IpcType.SET_VIDEO_ENABLED, new Msg.Flag(enabled).write());
    }

    @Override
    public void setVisible(boolean visible) {
        // Direct media may keep playing audibly while not visible (section 10.5); video upload is toggled instead.
        setVideoEnabled(visible);
    }

    @Override
    public void setVolume(float volume, boolean muted) {
        // Volume is applied by the Minecraft sound instance supplier.
    }

    @Override
    public void setCaptions(boolean enabled) {
        // Captions are rendered locally from cues; nothing to tell the worker.
    }

    @Override
    public void mouse(int kind, int x, int y, int button, int modifiers, int wheel) {
    }

    @Override
    public void key(int kind, int keyCode, int scanCode, int modifiers, int codePoint) {
    }

    @Override
    public void resize(int width, int height) {
        worker.send(IpcType.SET_OUTPUT, new Msg.SetOutput(width, height, 30).write());
    }

    @Override
    public void tick() {
        if (!worker.heartbeat()) {
            if (state != LocalPlaybackState.ERROR) {
                fail(ErrorCode.WORKER_HUNG, worker.exitReason());
            }
            return;
        }
        audio.tick();
        long consumed = audio.consumedBytes();
        if (consumed != lastCreditSent || lastCreditGeneration != generation) {
            lastCreditSent = consumed;
            lastCreditGeneration = generation;
            worker.send(IpcType.AUDIO_CREDIT, new Msg.AudioCredit(generation, consumed).write());
        }
        long pts = audio.presentedPtsNanos();
        int clockFlags = (audio.isAudible() || pts >= 0 ? Msg.CLOCK_AUDIO_ACTIVE : 0) | (playing ? Msg.CLOCK_PLAYING : 0);
        worker.send(IpcType.CLOCK_REPORT, new Msg.ClockReport(generation, System.nanoTime(), pts, clockFlags).write());
    }

    @Override
    public long observedPositionMs() {
        if ((flags & Msg.STATE_HAS_AUDIO) != 0) {
            long pts = audio.presentedPtsNanos();
            if (pts >= 0) {
                return pts / 1_000_000L;
            }
        }
        long framePts = lastFramePtsNanos;
        return framePts >= 0 ? framePts / 1_000_000L : positionMs;
    }

    @Override
    public Status status() {
        LocalPlaybackState shown = state;
        boolean endedNow = ended;
        if (shown == LocalPlaybackState.ENDED && (flags & Msg.STATE_HAS_AUDIO) != 0 && !audio.tailPlayed()) {
            // Everything has been delivered but the buffered tail is still being heard; ENDED (which a trusted viewer
            // reports to the server, advancing the queue) waits for it.
            shown = LocalPlaybackState.PLAYING;
            endedNow = false;
        }
        return new Status(shown, error, detail, positionMs, durationMs, live, capabilities, videoWidth, videoHeight,
                (flags & Msg.STATE_HAS_AUDIO) != 0, (flags & Msg.STATE_HAS_VIDEO) != 0, buffering, endedNow);
    }

    @Override
    public FrameExchange frames() {
        return frames;
    }

    @Override
    public TvAudioOutput audio() {
        return audio;
    }

    @Override
    public List<Cue> cues() {
        return cues;
    }

    @Override
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("backend: direct media (GStreamer worker, pid " + worker.pid() + ")");
        out.add("runtime: " + runtimeVersion);
        out.add("state: " + state + (error != ErrorCode.NONE ? " / " + error : "") + (detail.isEmpty() ? "" : " - " + detail));
        out.add("video: " + videoWidth + "x" + videoHeight + ", frames received " + frames.received() + ", dropped " + frames.dropped());
        out.add("audio: buffered " + audio.ring().bufferedNanos() / 1_000_000 + " ms, discontinuities " + audio.ring().discontinuities()
                + ", audible " + audio.isAudible());
        out.add("position: " + observedPositionMs() + " / " + durationMs + " ms" + (live ? " (live)" : ""));
        out.add("capabilities: " + capabilities);
        List<String> log = worker.recentLog();
        for (int i = Math.max(0, log.size() - 12); i < log.size(); i++) {
            out.add("worker: " + log.get(i));
        }
        return out;
    }

    /** Sorted position for a cue, or -1 if it is already present (an HLS re-open re-sends the sidecar). IPC thread only. */
    private int insertionPoint(Cue cue) {
        int lo = 0;
        int hi = cues.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs() <= cue.startMs()) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int i = lo - 1; i >= 0 && cues.get(i).startMs() == cue.startMs(); i--) {
            if (cues.get(i).equals(cue)) {
                return -1;
            }
        }
        return lo;
    }

    private void fail(ErrorCode code, String why) {
        error = code;
        detail = TextSanitizer.sanitize(why, 300);
        state = LocalPlaybackState.ERROR;
    }

    // ---- worker messages (reader thread) ---------------------------------------------------------------------------

    @Override
    public void onMessage(IpcType type, PayloadReader payload) throws IOException {
        switch (type) {
            case HELLO_ACK -> {
                Msg.HelloAck ack = Msg.HelloAck.read(payload);
                if (ack.protocol() != OteTvCore.IPC_PROTOCOL_VERSION) {
                    fail(ErrorCode.RUNTIME_INCOMPATIBLE, "worker protocol " + ack.protocol());
                    return;
                }
                runtimeVersion = TextSanitizer.sanitize(ack.runtimeVersion(), 128);
                rateControl = (ack.flags() & Msg.HelloAck.FLAG_RATE_CONTROL) != 0;
            }
            case STATE -> {
                Msg.State s = Msg.State.read(payload);
                if (s.generation() != generation) {
                    return;
                }
                LocalPlaybackState next = LocalPlaybackState.byOrdinal(s.state());
                if (state != LocalPlaybackState.ERROR || next != LocalPlaybackState.ERROR) {
                    state = next;
                }
                positionMs = Math.max(0, s.positionMs());
                durationMs = s.durationMs();
                flags = s.flags();
                live = (s.flags() & Msg.STATE_LIVE) != 0;
                buffering = s.bufferingPercent();
                videoWidth = s.videoWidth();
                videoHeight = s.videoHeight();
            }
            case CAPS -> {
                Msg.Caps c = Msg.Caps.read(payload);
                if (c.generation() == generation) {
                    capabilities = PlaybackCapabilities.fromBits(c.bits(), c.durationMs());
                }
            }
            case AUDIO_CHUNK -> {
                Msg.AudioHeader h = Msg.AudioHeader.read(payload);
                if (h.sampleRate() != 48_000 || h.channels() != 2 || payload.remaining() != h.byteLength()) {
                    throw new com.otectus.otetv.core.ipc.IpcProtocolException("unexpected audio format");
                }
                byte[] pcm = payload.bytes(h.byteLength());
                audio.ring().push(h.generation(), h.ptsNanos(), pcm);
            }
            case CUE -> {
                Msg.Cue c = Msg.Cue.read(payload);
                if (c.generation() == generation && cues.size() < MAX_CUES) {
                    String text = TextSanitizer.sanitizeLines(c.text(), 3, 160);
                    if (!text.isEmpty()) {
                        Cue cue = new Cue(c.startMs(), c.endMs(), text);
                        int at = insertionPoint(cue);
                        if (at >= 0) {
                            cues.add(at, cue);
                        }
                    }
                }
            }
            case CUES_CLEAR -> {
                if (Msg.Gen.read(payload).generation() == generation) {
                    cues.clear();
                }
            }
            case ERROR -> {
                Msg.Error e = Msg.Error.read(payload);
                if (e.generation() == generation || e.generation() == 0) {
                    fail(ErrorCode.byOrdinal(e.code()), e.detail());
                }
            }
            case EOS -> {
                if (Msg.Gen.read(payload).generation() == generation) {
                    ended = true;
                    state = LocalPlaybackState.ENDED;
                    audio.setEndOfStream();
                }
            }
            case TRACKS, PONG -> {
                // tracks are informational for diagnostics in this release; pong handled by WorkerProcess
            }
            default -> {
            }
        }
    }

    @Override
    public void onVideoFrame(FrameHeader header, IpcReader reader, int pixelBytes) throws IOException {
        if (header.generation() == generation) {
            lastFramePtsNanos = header.ptsNanos();
        }
        frames.receive(header, reader, pixelBytes);
    }

    @Override
    public void onExit(int exitCode, boolean requested, String reason) {
        if (!requested) {
            fail(ErrorCode.WORKER_CRASH, reason + " (exit " + exitCode + ")");
        }
    }

    @Override
    public void close() {
        audio.close();
        worker.close();
        frames.close();
    }
}
