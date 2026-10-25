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
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.source.YouTubeRef;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.text.TextSanitizer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The official YouTube embedded player hosted by the sandboxed native browser worker (section 12). Audio stays under
 * the browser player's control; this client only applies documented volume/mute operations. Controls mirror shared
 * intent through documented player operations; there is no ad detection, URL extraction or rate correction.
 */
public final class BrowserBackend implements PlaybackBackend, WorkerProcess.Listener {
    /** IFrame API player states. */
    private static final int YT_ENDED = 0;
    private static final int YT_PLAYING = 1;
    private static final int YT_PAUSED = 2;
    private static final int YT_BUFFERING = 3;

    private final WorkerProcess worker;
    private final FrameExchange frames = new FrameExchange();
    private volatile int generation;
    private volatile LocalPlaybackState state = LocalPlaybackState.LOADING;
    private volatile ErrorCode error = ErrorCode.NONE;
    private volatile String detail = "";
    private volatile long positionMs;
    private volatile long positionAtNanos = System.nanoTime();
    private volatile long durationMs = -1;
    private volatile boolean playing;
    private volatile boolean ended;
    private volatile boolean sandboxed;
    private volatile String runtimeVersion = "";
    private volatile int width;
    private volatile int height;
    private int lastVolume = -1;
    private boolean lastMuted;
    private boolean lastVisible = true;

    private BrowserBackend(RuntimeManager.LaunchSpec spec) throws IOException {
        this.worker = WorkerProcess.launch("browser worker", spec.command(), spec.environment(), spec.workDir(), this, false);
    }

    public static BrowserBackend start(RuntimeManager.LaunchSpec spec, int generation, YouTubeRef ref, long startMs, boolean autoplay,
                                       boolean captions, int viewportWidth, int viewportHeight) throws IOException {
        BrowserBackend b = new BrowserBackend(spec);
        b.generation = generation;
        b.frames.acceptGeneration(generation);
        b.worker.send(IpcType.HELLO, new Msg.Hello(OteTvCore.IPC_PROTOCOL_VERSION, OteTV.MOD_ID, Msg.ROLE_BROWSER, false).write());
        b.worker.send(IpcType.RESIZE, new Msg.Resize(viewportWidth, viewportHeight).write());
        int start = (int) Math.max(ref.startSeconds(), startMs / 1000);
        b.worker.send(IpcType.LOAD_VIDEO, new Msg.LoadVideo(generation, ref.videoId(), start, autoplay, captions).write());
        b.playing = autoplay;
        return b;
    }

    @Override
    public Kind kind() {
        return Kind.BROWSER;
    }

    @Override
    public void play() {
        playing = true;
        worker.send(IpcType.PLAY, new Msg.Gen(generation).write());
    }

    @Override
    public void pause() {
        playing = false;
        worker.send(IpcType.PAUSE, new Msg.Gen(generation).write());
    }

    @Override
    public void seek(long position, int newGeneration) {
        // The official player seeks in place; the generation stays tied to the loaded video.
        positionMs = position;
        positionAtNanos = System.nanoTime();
        worker.send(IpcType.SEEK, new Msg.Seek(generation, position, true).write());
    }

    @Override
    public void setRate(double rate) {
        // Never adjust YouTube playback rate for synchronization (section 10.4).
    }

    @Override
    public void setVideoEnabled(boolean enabled) {
        setVisible(enabled);
    }

    /** Local visibility handling: hidden players pause locally and never keep invisible audio going (section 12.4). */
    @Override
    public void setVisible(boolean visible) {
        if (visible != lastVisible) {
            lastVisible = visible;
            worker.send(IpcType.SET_VISIBLE, new Msg.Flag(visible).write());
        }
    }

    public boolean isVisibleToPlayer() {
        return lastVisible;
    }

    @Override
    public void setVolume(float volume, boolean muted) {
        int percent = Math.max(0, Math.min(100, Math.round(volume * 100)));
        if (Math.abs(percent - lastVolume) >= 2 || muted != lastMuted) {
            lastVolume = percent;
            lastMuted = muted;
            worker.send(IpcType.SET_VOLUME, new Msg.SetVolume(generation, percent, muted).write());
        }
    }

    @Override
    public void setCaptions(boolean enabled) {
        worker.send(IpcType.CAPTIONS_TOGGLE, new Msg.Flag(enabled).write());
    }

    @Override
    public void mouse(int kind, int x, int y, int button, int modifiers, int wheel) {
        worker.send(IpcType.MOUSE, new Msg.Mouse(generation, kind, x, y, button, modifiers, wheel).write());
    }

    @Override
    public void key(int kind, int keyCode, int scanCode, int modifiers, int codePoint) {
        worker.send(IpcType.KEY, new Msg.Key(generation, kind, keyCode, scanCode, modifiers, codePoint).write());
    }

    @Override
    public void resize(int w, int h) {
        worker.send(IpcType.RESIZE, new Msg.Resize(w, h).write());
    }

    @Override
    public void tick() {
        if (!worker.heartbeat() && state != LocalPlaybackState.ERROR) {
            error = ErrorCode.WORKER_HUNG;
            state = LocalPlaybackState.ERROR;
        }
    }

    @Override
    public long observedPositionMs() {
        long base = positionMs;
        if (state == LocalPlaybackState.PLAYING) {
            base += (System.nanoTime() - positionAtNanos) / 1_000_000L;
        }
        return durationMs > 0 ? Math.min(base, durationMs) : base;
    }

    @Override
    public Status status() {
        PlaybackCapabilities caps = PlaybackCapabilities.sourceDefaults(SourceType.YOUTUBE).withDuration(durationMs);
        return new Status(state, error, detail, observedPositionMs(), durationMs, false, caps, width, height, true, true,
                state == LocalPlaybackState.BUFFERING ? 50 : 100, ended);
    }

    @Override
    public FrameExchange frames() {
        return frames;
    }

    @Nullable
    @Override
    public TvAudioOutput audio() {
        return null;
    }

    @Override
    public List<Cue> cues() {
        return List.of();
    }

    @Override
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("backend: official YouTube player (native browser worker, pid " + worker.pid() + ")");
        out.add("runtime: " + runtimeVersion + (sandboxed ? ", sandboxed" : ", NOT sandboxed"));
        out.add("state: " + state + (error != ErrorCode.NONE ? " / " + error : "") + (detail.isEmpty() ? "" : " - " + detail));
        out.add("viewport: " + width + "x" + height + ", frames " + frames.received() + ", dropped " + frames.dropped());
        out.add("position: " + observedPositionMs() + " / " + durationMs + " ms, visible " + lastVisible);
        List<String> log = worker.recentLog();
        for (int i = Math.max(0, log.size() - 12); i < log.size(); i++) {
            out.add("worker: " + log.get(i));
        }
        return out;
    }

    // ---- worker messages ------------------------------------------------------------------------------------------

    @Override
    public void onMessage(IpcType type, PayloadReader payload) throws IOException {
        switch (type) {
            case HELLO_ACK -> {
                Msg.HelloAck ack = Msg.HelloAck.read(payload);
                runtimeVersion = TextSanitizer.sanitize(ack.runtimeVersion(), 128);
                sandboxed = (ack.flags() & Msg.HelloAck.FLAG_SANDBOXED) != 0;
                if (ack.protocol() != OteTvCore.IPC_PROTOCOL_VERSION) {
                    error = ErrorCode.RUNTIME_INCOMPATIBLE;
                    state = LocalPlaybackState.ERROR;
                }
            }
            case PLAYER_EVENT -> handlePlayerEvent(Msg.PlayerEvent.read(payload));
            case ERROR -> {
                Msg.Error e = Msg.Error.read(payload);
                error = ErrorCode.byOrdinal(e.code());
                detail = TextSanitizer.sanitize(e.detail(), 300);
                state = LocalPlaybackState.ERROR;
            }
            default -> {
            }
        }
    }

    private void handlePlayerEvent(Msg.PlayerEvent e) {
        if (e.generation() != generation) {
            return;
        }
        switch (e.event()) {
            case Msg.PlayerEvent.READY, Msg.PlayerEvent.LOADED -> {
                if (state == LocalPlaybackState.LOADING) {
                    state = playing ? LocalPlaybackState.BUFFERING : LocalPlaybackState.PAUSED;
                }
            }
            case Msg.PlayerEvent.STATE -> {
                int yt = (int) e.value();
                state = switch (yt) {
                    case YT_PLAYING -> LocalPlaybackState.PLAYING;
                    case YT_PAUSED -> lastVisible ? LocalPlaybackState.PAUSED : LocalPlaybackState.LOCALLY_PAUSED;
                    case YT_BUFFERING -> LocalPlaybackState.BUFFERING;
                    case YT_ENDED -> LocalPlaybackState.ENDED;
                    default -> state;
                };
                ended = yt == YT_ENDED;
                positionAtNanos = System.nanoTime();
            }
            case Msg.PlayerEvent.TIME -> {
                positionMs = Math.max(0, e.value());
                positionAtNanos = System.nanoTime();
            }
            case Msg.PlayerEvent.DURATION -> durationMs = e.value() > 0 ? e.value() : -1;
            case Msg.PlayerEvent.ERROR -> {
                error = ErrorCode.fromYouTubeError((int) e.value());
                detail = "YouTube error " + e.value();
                state = LocalPlaybackState.ERROR;
            }
            case Msg.PlayerEvent.AUTOPLAY_BLOCKED -> {
                error = ErrorCode.AUTOPLAY_BLOCKED;
                state = LocalPlaybackState.WAITING_FOR_GESTURE;
            }
            default -> {
            }
        }
    }

    @Override
    public void onVideoFrame(FrameHeader header, IpcReader reader, int pixelBytes) throws IOException {
        width = header.width();
        height = header.height();
        frames.receive(header, reader, pixelBytes);
    }

    @Override
    public void onExit(int exitCode, boolean requested, String reason) {
        if (!requested) {
            error = ErrorCode.WORKER_CRASH;
            detail = TextSanitizer.sanitize(reason + " (exit " + exitCode + ")", 300);
            state = LocalPlaybackState.ERROR;
        }
    }

    @Override
    public void close() {
        worker.close();
        frames.close();
    }
}
