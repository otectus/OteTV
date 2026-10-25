package com.otectus.otetv.client.tv;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import com.otectus.otetv.client.media.BrowserBackend;
import com.otectus.otetv.client.media.DirectMediaBackend;
import com.otectus.otetv.client.media.FrameExchange;
import com.otectus.otetv.client.media.PlaybackBackend;
import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.client.media.VideoTexture;
import com.otectus.otetv.client.render.TvProfile;
import com.otectus.otetv.client.render.TvProfiles;
import com.otectus.otetv.client.store.ConsentStore;
import com.otectus.otetv.client.store.LocalMediaLibrary;
import com.otectus.otetv.client.store.ServerContext;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.captions.CaptionParser;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.ipc.SourceGrant;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.state.MediaStateMachine;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.core.sync.DriftController;
import com.otectus.otetv.core.sync.TimelineAnchor;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One client's playback of one TV (section 8.1 ClientPlaybackSession): the desired shared state plus independent local
 * availability, consent, runtime and observed playback. Local problems (buffering, missing file, consent, hidden
 * window) never change the shared session; drift is corrected locally under the policy of section 10.4.
 */
public final class ClientPlaybackSession {
    public enum Mode { SHARED, PRIVATE }

    private static final long DRIFT_INTERVAL = 500_000_000L;
    private static final long STATUS_INTERVAL = 1_000_000_000L;
    /** Conservative projected-size threshold for the in-world YouTube player (our policy precaution, section 12.1). */
    public static final int YOUTUBE_MIN_PROJECTED_WIDTH = 320;
    public static final int YOUTUBE_MIN_PROJECTED_HEIGHT = 180;

    private final UUID tvId;
    private final Mode mode;
    private final MediaStateMachine machine = new MediaStateMachine();
    private final DriftController drift = new DriftController();
    private final VideoTexture texture = new VideoTexture();
    private BlockPos anchor = BlockPos.ZERO;
    private Direction facing = Direction.NORTH;
    private TvModel model = TvModel.CRT;
    private TvMount mount = TvMount.STAND;
    @Nullable
    private MediaSource source;
    private int serverGeneration = -1;
    private TimelineAnchor timeline = TimelineAnchor.STOPPED;
    private TimelineAnchor appliedAnchor;
    private float speakerGain = 0.8f;
    private VisualPreset preset = VisualPreset.CLEAR;
    private boolean powered;
    @Nullable
    private PlaybackBackend backend;
    private int flushGeneration = new SecureRandom().nextInt(1 << 20) + 1;
    private boolean localPlaying;
    private boolean allowedToRun = true;
    private long lastDrift;
    private long lastStatus;
    private LocalPlaybackState lastSentState;
    private List<Cue> localCues = List.of();
    private volatile long renderedAtNanos;
    private volatile double projectedWidth;
    private volatile double projectedHeight;
    private volatile boolean occluded;
    private boolean eligibleVisible = true;
    private long eligibilityChangedAt;
    private boolean youtubeHiddenPause;
    private boolean localMute;
    private boolean viewingMode;
    private long divergedSince = -1;
    private boolean diverged;
    private int startAttempts;
    private long retryAt = -1;
    private String waitingHost = "";
    private boolean unfocusedPause;
    private boolean endedReported;

    public ClientPlaybackSession(UUID tvId, Mode mode) {
        this.tvId = tvId;
        this.mode = mode;
    }

    public UUID tvId() {
        return tvId;
    }

    public Mode mode() {
        return mode;
    }

    public void setPlacement(BlockPos anchorPos, Direction facingDir, TvModel tvModel, TvMount tvMount) {
        this.anchor = anchorPos;
        this.facing = facingDir;
        this.model = tvModel;
        this.mount = tvMount;
    }

    public BlockPos anchor() {
        return anchor;
    }

    @Nullable
    public MediaSource source() {
        return source;
    }

    public boolean isYouTube() {
        return source != null && source.type() == SourceType.YOUTUBE;
    }

    public LocalPlaybackState state() {
        return machine.state();
    }

    public ErrorCode error() {
        return machine.error();
    }

    public String errorDetail() {
        return machine.detail();
    }

    public String waitingHost() {
        return waitingHost;
    }

    public boolean isDiverged() {
        return diverged;
    }

    public VisualPreset preset() {
        return preset;
    }

    @Nullable
    public PlaybackBackend backend() {
        return backend;
    }

    public boolean isLocalMuted() {
        return localMute;
    }

    public void setLocalMute(boolean value) {
        localMute = value;
    }

    public void setViewingMode(boolean value) {
        viewingMode = value;
        if (backend instanceof DirectMediaBackend direct) {
            direct.configureAudio(value, audioRange());
        }
    }

    public boolean isViewingMode() {
        return viewingMode;
    }

    public void setAllowedToRun(boolean allowed) {
        if (allowed == allowedToRun) {
            return;
        }
        allowedToRun = allowed;
        if (!allowed) {
            stopBackend();
            machine.force(LocalPlaybackState.EXCESS_SESSION, System.nanoTime());
        } else if (source != null && powered) {
            restart();
        }
    }

    public boolean isAllowedToRun() {
        return allowedToRun;
    }

    // ---- shared state ---------------------------------------------------------------------------------------------

    /** Applies the latest shared snapshot; restarts on a new source or generation. */
    public void onShared(TvSnapshot s) {
        if (mode != Mode.SHARED) {
            return;
        }
        powered = s.powered();
        speakerGain = s.speakerGain();
        preset = s.preset();
        timeline = s.timeline();
        MediaSource newSource = s.powered() ? s.source() : null;
        boolean changed = newSource == null ? source != null
                : source == null || !newSource.contentIdentity().equals(source.contentIdentity()) || s.sourceGeneration() != serverGeneration;
        serverGeneration = s.sourceGeneration();
        if (newSource == null) {
            source = null;
            stopBackend();
            machine.force(LocalPlaybackState.IDLE, System.nanoTime());
            return;
        }
        if (changed) {
            source = newSource;
            endedReported = false;
            startAttempts = 0;
            restart();
        }
    }

    /** Starts a private local-file playback on this TV for this player only (section 11.4). */
    public void startPrivate(LocalMediaLibrary.Entry entry) throws Exception {
        source = MediaSource.localFile(entry.key(), entry.label());
        powered = true;
        timeline = new TimelineAnchor(PlayState.PLAYING, 0, 0, 1.0, -1, false);
        restart();
    }

    // ---- prerequisites and start --------------------------------------------------------------------------------------

    public void restart() {
        stopBackend();
        diverged = false;
        divergedSince = -1;
        appliedAnchor = null;
        if (source == null || !allowedToRun) {
            return;
        }
        long now = System.nanoTime();
        machine.beginGeneration(serverGeneration, now);
        if (source.type().requiresNetwork()) {
            if (OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.get() || OteTvClientConfig.EXTERNAL_MEDIA.get() == OteTvClientConfig.ExternalMedia.DENY) {
                machine.fail(serverGeneration, ErrorCode.EXTERNAL_MEDIA_DISABLED, "", now);
                return;
            }
            String target = source.networkHost().orElse("");
            if (!ConsentStore.isGranted(ServerContext.current(), target)) {
                waitingHost = target;
                machine.force(LocalPlaybackState.NEEDS_CONSENT, now);
                return;
            }
        }
        waitingHost = "";
        try {
            if (source.type() == SourceType.YOUTUBE) {
                startBrowser();
            } else {
                startDirect();
            }
        } catch (MissingRuntime e) {
            machine.fail(serverGeneration, ErrorCode.RUNTIME_MISSING, e.getMessage(), now);
        } catch (MissingFile e) {
            machine.fail(serverGeneration, e.mismatch ? ErrorCode.LOCAL_FILE_MISMATCH : ErrorCode.LOCAL_FILE_ABSENT, "", now);
        } catch (IOException e) {
            machine.fail(serverGeneration, ErrorCode.WORKER_CRASH, e.getMessage(), now);
            scheduleRetry();
        }
    }

    private static final class MissingRuntime extends Exception {
        MissingRuntime(String m) {
            super(m);
        }
    }

    private static final class MissingFile extends Exception {
        final boolean mismatch;

        MissingFile(boolean mismatch) {
            this.mismatch = mismatch;
        }
    }

    private void scheduleRetry() {
        // One automatic retry for a transient startup problem, then the player decides (section 17.3).
        if (startAttempts < 1) {
            startAttempts++;
            retryAt = System.nanoTime() + 2_000_000_000L;
        }
    }

    public void retryNow() {
        startAttempts = 0;
        restart();
    }

    private void startDirect() throws IOException, MissingRuntime, MissingFile {
        RuntimeManager.MediaRuntimeInfo info = RuntimeManager.mediaInfo();
        if (info.status() == RuntimeManager.MediaStatus.UNKNOWN || info.status() == RuntimeManager.MediaStatus.PROBING) {
            machine.force(LocalPlaybackState.LOADING, System.nanoTime());
            RuntimeManager.probeMedia().thenRun(() -> Minecraft.getInstance().execute(() -> {
                if (backend == null && source != null && machine.state() == LocalPlaybackState.LOADING) {
                    restart();
                }
            }));
            return;
        }
        if (info.status() != RuntimeManager.MediaStatus.AVAILABLE) {
            throw new MissingRuntime(info.detail());
        }
        SourceGrant.Kind kind;
        String location;
        String captions = null;
        localCues = List.of();
        boolean live = false;
        switch (source.type()) {
            case BUNDLED -> {
                kind = SourceGrant.Kind.FILE;
                location = RuntimeManager.bundledAsset(source.assetId().orElse("")).toString();
                try {
                    byte[] vtt = RuntimeManager.bundledCaptions(source.assetId().orElse(""));
                    if (vtt.length > 0) {
                        localCues = CaptionParser.parse(vtt);
                    }
                } catch (CaptionParser.CaptionException e) {
                    OteTV.LOGGER.warn("Bundled captions are invalid: {}", e.getMessage());
                }
            }
            case HTTPS_VIDEO -> {
                kind = SourceGrant.Kind.HTTPS;
                location = source.url().orElseThrow().toString();
                captions = source.captionsUrl().map(Object::toString).orElse(null);
            }
            case HLS_CLEAR -> {
                kind = SourceGrant.Kind.HLS;
                location = source.url().orElseThrow().toString();
                captions = source.captionsUrl().map(Object::toString).orElse(null);
            }
            case SHARED_LOCAL_ASSET -> {
                LocalMediaLibrary.Entry entry = LocalMediaLibrary.byDigest(source.sha256().orElse("")).orElseThrow(() -> new MissingFile(false));
                Path path = LocalMediaLibrary.resolveForPlayback(entry).orElseThrow(() -> new MissingFile(true));
                kind = SourceGrant.Kind.FILE;
                location = path.toString();
            }
            case LOCAL_FILE -> {
                LocalMediaLibrary.Entry entry = LocalMediaLibrary.byKey(source.localKey().orElse("")).orElseThrow(() -> new MissingFile(false));
                Path path = LocalMediaLibrary.resolveForPlayback(entry).orElseThrow(() -> new MissingFile(true));
                kind = SourceGrant.Kind.FILE;
                location = path.toString();
            }
            default -> throw new IOException("unsupported source type");
        }
        OteTvClientConfig.QualityPreset q = OteTvClientConfig.QUALITY_PRESET.get();
        long start = mode == Mode.PRIVATE ? 0 : targetPosition();
        boolean startPlaying = mode == Mode.PRIVATE || isSharedPlayingNow();
        flushGeneration++;
        List<com.otectus.otetv.core.net.LanGrant> lan = ConsentStore.lanGrants();
        if (lan.size() > SourceGrant.MAX_LAN_GRANTS) {
            lan = lan.subList(0, SourceGrant.MAX_LAN_GRANTS);
        }
        SourceGrant grant = new SourceGrant(flushGeneration, kind, location, captions, lan, q.width, q.height, q.fps, start, 0,
                !startPlaying, true, live);
        DirectMediaBackend direct = DirectMediaBackend.start(RuntimeManager.mediaLaunchSpec(), grant, this::effectiveVolume, this::audioPosition);
        direct.configureAudio(viewingMode, audioRange());
        direct.setMonochrome(effectsEnabled() && TvProfiles.get(model, mount).monochrome());
        backend = direct;
        localPlaying = startPlaying;
        appliedAnchor = timeline;
        drift.noteSeek(System.nanoTime() / 1_000_000L);
    }

    private void startBrowser() throws IOException, MissingRuntime {
        RuntimeManager.BrowserRuntimeInfo info = RuntimeManager.browserInfo();
        if (info.status() != RuntimeManager.BrowserStatus.INSTALLED) {
            throw new MissingRuntime(info.status() == RuntimeManager.BrowserStatus.UNSUPPORTED_PLATFORM ? "unsupported platform" : "browser runtime not installed");
        }
        boolean playing = isSharedPlayingNow();
        long start = targetPosition();
        flushGeneration++;
        backend = BrowserBackend.start(RuntimeManager.browserLaunchSpec(), flushGeneration, source.youtube().orElseThrow(), start, playing,
                OteTvClientConfig.CAPTIONS.get(), 1280, 720);
        localPlaying = playing;
        appliedAnchor = timeline;
    }

    private void stopBackend() {
        if (backend != null) {
            backend.close();
            backend = null;
        }
        localPlaying = false;
    }

    // ---- timing -------------------------------------------------------------------------------------------------------

    private long serverNow() {
        long now = ClientTvManager.get().clock().serverNow();
        // Before the first clock sample, treat the anchor as "now": positions hold until the estimate arrives.
        return now >= 0 ? now : timeline.anchorServerNanos();
    }

    private boolean isSharedPlayingNow() {
        return timeline.state() == PlayState.PLAYING && timeline.hasStarted(serverNow());
    }

    public long targetPosition() {
        return timeline.targetPositionMs(serverNow());
    }

    // ---- per tick -------------------------------------------------------------------------------------------------------

    public void tick() {
        long now = System.nanoTime();
        if (backend == null) {
            if (retryAt > 0 && now >= retryAt) {
                retryAt = -1;
                restart();
            }
            return;
        }
        backend.tick();
        PlaybackBackend.Status st = backend.status();
        observe(st, now);
        if (machine.state().isFailure()) {
            if (machine.error() == ErrorCode.WORKER_CRASH && retryAt < 0) {
                stopBackend();
                scheduleRetry();
            }
            return;
        }
        applyFocusPolicy();
        if (mode == Mode.SHARED) {
            applyIntent(now);
            if (backend instanceof DirectMediaBackend && now - lastDrift >= DRIFT_INTERVAL) {
                lastDrift = now;
                correctDrift(st);
            } else if (backend instanceof BrowserBackend) {
                watchDivergence(st, now);
            }
            reportStatus(st, now);
        }
        applyVisibility(now);
        if (backend instanceof BrowserBackend browser) {
            browser.setVolume(effectiveVolume() * distanceFactor(), effectiveVolume() <= 0);
        }
    }

    private void observe(PlaybackBackend.Status st, long now) {
        LocalPlaybackState s = st.state();
        if (s.isFailure()) {
            machine.fail(machine.generation(), st.error(), st.detail(), now);
        } else if (unfocusedPause || youtubeHiddenPause) {
            machine.force(LocalPlaybackState.LOCALLY_PAUSED, now);
        } else if (diverged) {
            machine.force(LocalPlaybackState.DIVERGED, now);
        } else {
            machine.force(s, now);
        }
    }

    /** Mirrors shared play/pause/seek intent into the backend. */
    private void applyIntent(long nowNanos) {
        if (backend == null || unfocusedPause || youtubeHiddenPause) {
            return;
        }
        boolean anchorChanged = appliedAnchor == null || appliedAnchor.state() != timeline.state()
                || appliedAnchor.positionMs() != timeline.positionMs() || appliedAnchor.anchorServerNanos() != timeline.anchorServerNanos();
        boolean wantPlaying = isSharedPlayingNow();
        if (wantPlaying && (!localPlaying || anchorChanged)) {
            seekBackend(targetPosition());
            backend.play();
            localPlaying = true;
            appliedAnchor = timeline;
        } else if (!wantPlaying && timeline.state() == PlayState.PLAYING && anchorChanged) {
            // Scheduled start in the future: show the starting frame and wait.
            if (localPlaying) {
                backend.pause();
                localPlaying = false;
            }
            seekBackend(timeline.positionMs());
            appliedAnchor = timeline;
        } else if (timeline.state() != PlayState.PLAYING && (localPlaying || anchorChanged)) {
            backend.pause();
            localPlaying = false;
            seekBackend(timeline.positionMs());
            appliedAnchor = timeline;
        }
    }

    private void seekBackend(long positionMs) {
        if (backend == null || timeline.live()) {
            return;
        }
        PlaybackCapabilities caps = backend.status().capabilities();
        if (backend instanceof DirectMediaBackend && !caps.equals(PlaybackCapabilities.NONE) && !caps.has(PlaybackCapabilities.Flag.SEEK)) {
            return;
        }
        backend.seek(positionMs, ++flushGeneration);
        drift.noteSeek(System.nanoTime() / 1_000_000L);
    }

    private void correctDrift(PlaybackBackend.Status st) {
        if (backend == null || !localPlaying || st.state() != LocalPlaybackState.PLAYING || !ClientTvManager.get().clock().hasEstimate()) {
            return;
        }
        long observed = backend.observedPositionMs();
        long target = targetPosition();
        boolean seekable = !st.live() && !timeline.live() && st.capabilities().has(PlaybackCapabilities.Flag.SEEK);
        DriftController.Correction c = drift.update(System.nanoTime() / 1_000_000L, observed, target,
                st.capabilities().has(PlaybackCapabilities.Flag.RATE_CONTROL), seekable);
        switch (c.kind()) {
            case SEEK -> backend.seek(c.seekToMs(), ++flushGeneration);
            case RATE -> backend.setRate(c.rate());
            default -> {
            }
        }
    }

    /** YouTube: best effort; prolonged mismatch is reported as local divergence, never chased (section 10.4). */
    private void watchDivergence(PlaybackBackend.Status st, long now) {
        if (st.state() != LocalPlaybackState.PLAYING || timeline.state() != PlayState.PLAYING) {
            divergedSince = -1;
            return;
        }
        long mismatch = Math.abs(backend.observedPositionMs() - targetPosition());
        if (mismatch > 4000) {
            if (divergedSince < 0) {
                divergedSince = now;
            } else if (now - divergedSince > 5_000_000_000L) {
                diverged = true;
            }
        } else {
            divergedSince = -1;
            diverged = false;
        }
    }

    /** Local action: return to the shared position after divergence. */
    public void rejoinCurrentPosition() {
        if (backend != null) {
            backend.seek(targetPosition(), ++flushGeneration);
            if (isSharedPlayingNow()) {
                backend.play();
            }
        }
        diverged = false;
        divergedSince = -1;
    }

    private void reportStatus(PlaybackBackend.Status st, long now) {
        LocalPlaybackState s = machine.state();
        // At most one report per second per TV, matching the server's limit (section 9.3).
        if (now - lastStatus < STATUS_INTERVAL) {
            return;
        }
        lastStatus = now;
        lastSentState = s;
        if (s == LocalPlaybackState.ENDED) {
            if (endedReported) {
                return;
            }
            endedReported = true;
        }
        long position = backend == null ? -1 : Math.max(0, backend.observedPositionMs());
        OteTvNetwork.sendToServer(new C2S.ViewerStatus(tvId, serverGeneration, s, Math.min(position, com.otectus.otetv.core.Limits.MAX_POSITION_MS),
                st.durationMs(), st.live()));
    }

    // ---- visibility, focus, volume --------------------------------------------------------------------------------------

    /** Called by the renderer each frame the TV is drawn, with its projected on-screen size and occlusion. */
    public void markRendered(double widthPx, double heightPx, boolean isOccluded) {
        renderedAtNanos = System.nanoTime();
        projectedWidth = widthPx;
        projectedHeight = heightPx;
        occluded = isOccluded;
    }

    public void markViewed() {
        renderedAtNanos = System.nanoTime();
        projectedWidth = Double.MAX_VALUE;
        projectedHeight = Double.MAX_VALUE;
        occluded = false;
    }

    public boolean tooSmallForYouTube() {
        return isYouTube() && !viewingMode && (projectedWidth < YOUTUBE_MIN_PROJECTED_WIDTH || projectedHeight < YOUTUBE_MIN_PROJECTED_HEIGHT);
    }

    private void applyVisibility(long now) {
        if (backend == null) {
            return;
        }
        boolean rendered = now - renderedAtNanos < 300_000_000L;
        if (backend instanceof DirectMediaBackend direct) {
            direct.setVideoEnabled(rendered || viewingMode);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean foreignScreen = mc.screen != null && !(viewingMode && mc.screen instanceof com.otectus.otetv.client.screen.ViewingScreen);
        boolean eligible = rendered && !occluded && !tooSmallForYouTube() && mc.isWindowActive() && !foreignScreen;
        if (eligible != eligibleVisible) {
            long debounce = eligible ? 500_000_000L : 250_000_000L;
            if (eligibilityChangedAt == 0) {
                eligibilityChangedAt = now;
            } else if (now - eligibilityChangedAt >= debounce) {
                eligibleVisible = eligible;
                eligibilityChangedAt = 0;
                backend.setVisible(eligible);
                if (!eligible && localPlaying) {
                    // Hidden YouTube pauses locally; resuming needs a real local interaction (section 12.4).
                    youtubeHiddenPause = true;
                    localPlaying = false;
                }
            }
        } else {
            eligibilityChangedAt = 0;
        }
    }

    public boolean isWaitingForLocalResume() {
        return youtubeHiddenPause || machine.state() == LocalPlaybackState.WAITING_FOR_GESTURE;
    }

    /** The player clicked "resume": forward a genuine click to the official player, then follow shared intent. */
    public void localResume() {
        youtubeHiddenPause = false;
        if (backend instanceof BrowserBackend browser) {
            browser.mouse(com.otectus.otetv.core.ipc.Msg.Mouse.DOWN, 640, 360, 0, 0, 0);
            browser.mouse(com.otectus.otetv.core.ipc.Msg.Mouse.UP, 640, 360, 0, 0, 0);
        }
        appliedAnchor = null;
        if (mode == Mode.PRIVATE && backend != null) {
            backend.play();
            localPlaying = true;
        }
    }

    private void applyFocusPolicy() {
        boolean pauseUnfocused = OteTvClientConfig.PAUSE_WHEN_UNFOCUSED.get() || isYouTube();
        boolean active = Minecraft.getInstance().isWindowActive();
        if (pauseUnfocused && !active && !unfocusedPause && localPlaying && backend != null) {
            backend.pause();
            localPlaying = false;
            unfocusedPause = true;
        } else if (unfocusedPause && active) {
            unfocusedPause = false;
            appliedAnchor = null; // resync to the shared position
            if (mode == Mode.PRIVATE && backend != null) {
                backend.play();
                localPlaying = true;
            }
        }
    }

    public int audioRange() {
        return Math.max(4, Math.min(64, OteTvClientConfig.MAX_AUDIO_RANGE.get()));
    }

    public float effectiveVolume() {
        if (localMute || OteTvClientConfig.GLOBAL_MUTE.get()) {
            return 0f;
        }
        return (float) (OteTvClientConfig.LOCAL_VOLUME.get() * speakerGain);
    }

    private float distanceFactor() {
        if (viewingMode) {
            return 1f;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0f;
        }
        double d = player.position().distanceTo(audioPosition());
        return (float) Math.max(0, 1 - d / audioRange());
    }

    public Vec3 audioPosition() {
        return TvProfiles.get(model, mount).apertureCenter(anchor, facing);
    }

    public boolean effectsEnabled() {
        return !isYouTube() && preset == VisualPreset.AUTHENTIC && !OteTvClientConfig.DISABLE_PICTURE_EFFECTS.get()
                && !OteTvClientConfig.SAFE_MODE.get();
    }

    // ---- rendering support ----------------------------------------------------------------------------------------------

    /** Render thread: uploads the newest frame, returns the texture if it has content. */
    @Nullable
    public VideoTexture prepareFrame() {
        if (backend == null) {
            return texture.hasContent() && machine.state() == LocalPlaybackState.ENDED ? texture : null;
        }
        FrameExchange.Slot slot = backend.frames().takeLatest();
        if (slot != null) {
            var h = slot.header();
            texture.upload(slot.buffer(), h.width(), h.height(), h.stride(), h.format());
        }
        return texture.hasContent() ? texture : null;
    }

    @Nullable
    public Cue activeCue() {
        if (!OteTvClientConfig.CAPTIONS.get() || backend == null) {
            return null;
        }
        List<Cue> cues = !localCues.isEmpty() ? localCues : backend.cues();
        if (cues.isEmpty()) {
            return null;
        }
        return CaptionParser.activeCue(cues, backend.observedPositionMs());
    }

    public boolean hasCaptions() {
        return !localCues.isEmpty() || (backend != null && !backend.cues().isEmpty()) || isYouTube();
    }

    public long positionMs() {
        return backend == null ? targetPosition() : backend.observedPositionMs();
    }

    public long durationMs() {
        return backend == null ? timeline.durationMs() : backend.status().durationMs();
    }

    // ---- private-mode local controls --------------------------------------------------------------------------------

    public void localTogglePause() {
        if (backend == null) {
            return;
        }
        if (localPlaying) {
            backend.pause();
            localPlaying = false;
        } else {
            backend.play();
            localPlaying = true;
        }
    }

    public void localSeekRelative(long deltaMs) {
        if (backend != null) {
            backend.seek(Math.max(0, backend.observedPositionMs() + deltaMs), ++flushGeneration);
        }
    }

    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("tv: " + tvId + " (" + mode.name().toLowerCase(java.util.Locale.ROOT) + ")");
        out.add("source: " + (source == null ? "-" : source.type().id() + " " + com.otectus.otetv.core.redact.Redactor.redact(source.displayLabel())));
        out.add("local state: " + machine.state() + (machine.error() != ErrorCode.NONE ? " / " + machine.error() : ""));
        out.add("shared: " + timeline.state() + " target " + targetPosition() + " ms, generation " + serverGeneration);
        out.add("clock: rtt " + ClientTvManager.get().clock().rttMs() + " ms, uncertainty " + ClientTvManager.get().clock().uncertaintyMs() + " ms");
        if (backend != null) {
            out.addAll(backend.diagnostics());
        }
        return out;
    }

    public void close() {
        stopBackend();
        texture.release();
    }
}
