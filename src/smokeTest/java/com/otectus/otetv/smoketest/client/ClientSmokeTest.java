package com.otectus.otetv.smoketest.client;

import com.google.gson.GsonBuilder;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.client.media.PlaybackBackend;
import com.otectus.otetv.client.media.RuntimeInstaller;
import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.client.screen.DiagnosticsScreen;
import com.otectus.otetv.client.screen.RemoteScreen;
import com.otectus.otetv.client.screen.SourcePickerScreen;
import com.otectus.otetv.client.screen.TvMenuScreen;
import com.otectus.otetv.client.screen.TvSettingsScreen;
import com.otectus.otetv.client.screen.ViewingScreen;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.store.ConsentStore;
import com.otectus.otetv.client.store.ServerContext;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.capability.CodecMatrix;
import com.otectus.otetv.core.json.RuntimeManifestJson;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.runtime.RuntimeBundle;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceInput;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.session.ServerSourcePolicy;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSessionService;
import com.otectus.otetv.smoketest.SmokeTestAgent;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Drives a dev client into a fresh flat world and checks real in-world playback end to end (section 21.3 evidence on
 * this machine): a plasma TV is placed through the item, tuned to the bundled demo channel, joined from the client,
 * decoded by the real media worker, uploaded to the TV texture and heard through OpenAL. It samples the shared-timeline
 * error, pauses, seeks and resumes through the client's own action packets, checks captions, opens every TV screen,
 * saves screenshots to {@code run/screenshots/otetv_smoke_*.png}, writes {@code smoketest-result.json} and quits.
 */
@Mod.EventBusSubscriber(modid = SmokeTestAgent.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final String OUTPUT = System.getProperty(SmokeTestAgent.OUTPUT_PROPERTY);
    /** Optional YouTube phase: a video id, plus the directory holding runtime-packaging's bundle and manifest. */
    private static final String YOUTUBE = System.getProperty("otetv.smoketest.youtube");
    private static final String BROWSER_BUNDLES = System.getProperty("otetv.smoketest.browserBundles");
    private static final Map<String, Object> checks = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final long START = Util.getMillis();
    private static final int STEP_TIMEOUT = 20 * 90;

    private record Step(String name, BooleanSupplier ready, Runnable action) {
    }

    private static final List<Step> steps = new ArrayList<>();
    private static int stepIndex;
    private static int stepTicks;
    private static int titleTicks;
    private static int worldTicks;
    private static boolean worldRequested;
    private static boolean finished;
    private static volatile BlockPos anchor;
    private static volatile UUID tvId;
    private static String pendingShot;
    private static int pendingFrames;
    private static int pendingTicks;
    private static long waitUntil;
    private static long pausedPosition;
    private static long pausedFrames;
    private static boolean sawCue;
    private static final List<Long> syncErrors = new ArrayList<>();
    private static volatile S2C.ActionResult lastResult;
    private static RuntimeInstaller.Job installJob;
    private static volatile BlockPos viewerSpot;
    private static final List<String> youtubeTrace = new ArrayList<>();
    private static String lastTraceEntry = "";

    /** Records state changes (state, observed position, shared target) for the YouTube phase report. */
    private static void traceYouTube() {
        ClientPlaybackSession s = session();
        if (s == null || youtubeTrace.size() > 200) {
            return;
        }
        String entry = s.state() + (s.isWaitingForLocalResume() ? "/waiting" : "") + (s.isDiverged() ? "/diverged" : "");
        if (!entry.equals(lastTraceEntry)) {
            lastTraceEntry = entry;
            youtubeTrace.add((Util.getMillis() - START) + "ms " + entry + " at " + s.positionMs() + " target " + s.targetPosition());
        }
    }

    private ClientSmokeTest() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (OUTPUT == null || finished || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null) {
                if (mc.screen instanceof AccessibilityOnboardingScreen) {
                    // First launch of a fresh run directory shows the onboarding screen instead of the title screen.
                    mc.options.onboardAccessibility = false;
                    mc.options.save();
                    mc.setScreen(new TitleScreen());
                } else if (mc.screen instanceof TitleScreen && ++titleTicks == 40 && !worldRequested) {
                    worldRequested = true;
                    mc.getWindow().setWindowed(1280, 720);
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    // The test window may not have focus; captions are shown so the screenshots prove them too.
                    OteTvClientConfig.PAUSE_WHEN_UNFOCUSED.set(false);
                    OteTvClientConfig.CAPTIONS.set(true);
                    checks.put("title_ms", Util.getMillis() - START);
                    createWorld(mc);
                }
                return;
            }
            if (++worldTicks < 60) {
                return;
            }
            if (pendingShot != null) {
                if (++pendingTicks > 100) {
                    finish(mc, false, "screenshot never rendered: " + pendingShot);
                }
                return;
            }
            if (steps.isEmpty()) {
                plan(mc);
            }
            if (stepIndex >= steps.size()) {
                finish(mc, allPassed(), "complete");
                return;
            }
            Step step = steps.get(stepIndex);
            if (step.ready().getAsBoolean()) {
                log("step " + step.name());
                step.action().run();
                stepIndex++;
                stepTicks = 0;
            } else if (++stepTicks > STEP_TIMEOUT) {
                checks.put("timeout_" + step.name(), false);
                finish(mc, false, "timed out waiting for " + step.name() + state());
            }
        } catch (Throwable t) {
            OteTV.LOGGER.error("SMOKETEST failed", t);
            checks.put("exception", t.toString());
            finish(mc, false, "exception: " + t);
        }
    }

    private static void plan(Minecraft mc) {
        ClientTvManager.get().addResultListener(r -> lastResult = r);
        steps.add(new Step("probe_runtime", () -> true, RuntimeManager::probeMedia));
        steps.add(new Step("runtime_ready", () -> {
            RuntimeManager.MediaStatus s = RuntimeManager.mediaInfo().status();
            return s != RuntimeManager.MediaStatus.UNKNOWN && s != RuntimeManager.MediaStatus.PROBING;
        }, () -> {
            RuntimeManager.MediaRuntimeInfo info = RuntimeManager.mediaInfo();
            checks.put("runtime_available", info.status() == RuntimeManager.MediaStatus.AVAILABLE);
            checks.put("runtime", info.status() + " " + info.runtimeVersion() + (info.detail().isEmpty() ? "" : " (" + info.detail() + ")"));
            boolean webm = CodecMatrix.supports("webm_vp8_vorbis", info.elements());
            boolean ogg = CodecMatrix.supports("ogg_theora_vorbis", info.elements());
            checks.put("demo_encoding", !webm && ogg ? "welcome.ogv (no Matroska demuxer)" : "welcome.webm");
        }));
        steps.add(new Step("stage_tv", () -> true, () -> stage(mc)));
        steps.add(new Step("tv_on_client", () -> clientTv(mc) != null && clientTv(mc).publicState().powered(), () -> {
            aim(mc);
            mc.options.hideGui = true;
            ClientTvManager.get().join(tvId);
        }));
        steps.add(new Step("playing", () -> {
            ClientPlaybackSession s = session();
            return s != null && s.state() == LocalPlaybackState.PLAYING && frames() > 10;
        }, () -> {
            checks.put("first_picture_ms", Util.getMillis() - START);
            syncErrors.clear();
            waitUntil = Util.getMillis() + 6000;
        }));
        steps.add(new Step("sample_playback", () -> sample() && Util.getMillis() >= waitUntil, () -> {
            report("playback");
            PlaybackBackend b = session().backend();
            checks.put("video_size", b.status().videoWidth() + "x" + b.status().videoHeight());
            checks.put("frames_received", b.frames().received());
            checks.put("frames_dropped", b.frames().dropped());
            checks.put("audio_audible", b.audio().isAudible());
            checks.put("captions_seen", sawCue);
            shot("playing");
        }));
        steps.add(new Step("pause", () -> true, () -> ClientTvManager.get().sendAction(tvId, TvActionType.PAUSE)));
        steps.add(new Step("paused", () -> session().state() == LocalPlaybackState.PAUSED, () -> {
            pausedPosition = session().positionMs();
            pausedFrames = frames();
            waitUntil = Util.getMillis() + 1500;
        }));
        steps.add(new Step("paused_holds", () -> Util.getMillis() >= waitUntil, () -> {
            long drift = Math.abs(session().positionMs() - pausedPosition);
            checks.put("paused_position_drift_ms", drift);
            checks.put("paused_position_stable", drift <= 100);
            checks.put("paused_no_new_frames", frames() - pausedFrames <= 1);
            // Captions and the remote read this position, so it must match the shared timeline while paused too.
            long offset = Math.abs(session().positionMs() - session().targetPosition());
            checks.put("paused_position_vs_shared_ms", offset);
            checks.put("paused_position_matches_shared", offset <= 250);
            shot("paused");
        }));
        steps.add(new Step("seek", () -> true, () -> {
            ClientTvManager.get().sendAction(tvId, TvActionType.SEEK_ABSOLUTE, 30_000, 0, "", null, null);
            ClientTvManager.get().sendAction(tvId, TvActionType.PLAY);
            sawCue = false;
        }));
        steps.add(new Step("resumed_at_target", () -> {
            ClientPlaybackSession s = session();
            return s.state() == LocalPlaybackState.PLAYING && s.positionMs() >= 30_000 && s.positionMs() < 40_000;
        }, () -> {
            checks.put("seek_landed_ms", session().positionMs());
            syncErrors.clear();
            waitUntil = Util.getMillis() + 4000;
        }));
        steps.add(new Step("sample_after_seek", () -> sample() && Util.getMillis() >= waitUntil, () -> {
            report("after_seek");
            checks.put("captions_after_seek", sawCue);
            shot("after_seek");
        }));
        steps.add(new Step("menu", () -> true, () -> {
            mc.options.hideGui = false;
            mc.setScreen(new TvMenuScreen(tvId));
            shot("menu");
        }));
        steps.add(new Step("settings", () -> true, () -> {
            mc.setScreen(new TvSettingsScreen(null, tvId));
            shot("settings");
        }));
        steps.add(new Step("sources", () -> true, () -> {
            mc.setScreen(new SourcePickerScreen(null, tvId));
            shot("sources");
        }));
        steps.add(new Step("remote", () -> true, () -> {
            mc.setScreen(new RemoteScreen(null, tvId, null));
            shot("remote");
        }));
        steps.add(new Step("diagnostics", () -> true, () -> {
            mc.setScreen(new DiagnosticsScreen(null, tvId));
            shot("diagnostics");
        }));
        steps.add(new Step("viewing", () -> true, () -> {
            mc.setScreen(new ViewingScreen(null, tvId));
            shot("viewing");
        }));
        steps.add(new Step("close", () -> true, () -> {
            mc.setScreen(null);
            checks.put("session_diagnostics", session().diagnostics());
        }));
        if (YOUTUBE != null && BROWSER_BUNDLES != null) {
            planYouTube(mc);
        }
    }

    /**
     * The official YouTube player through the native browser worker: installed from the packaged bundle with the
     * client's own verified installer, consent granted as the consent screen's Allow button does, OteTV muted so an
     * unattended run is silent, and the player standing close enough for the projected-size rule. The window's focus
     * flag is set the way GLFW's focus callback sets it, so the run never steals desktop focus.
     */
    private static void planYouTube(Minecraft mc) {
        steps.add(new Step("install_browser_runtime", () -> true, () -> {
            try {
                Path dir = Path.of(BROWSER_BUNDLES);
                RuntimeBundle bundle = RuntimeManifestJson.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8)).get(0);
                installJob = RuntimeInstaller.installFromFile(bundle, dir.resolve("otetv-browser-linux-x86_64-" + bundle.version() + ".zip"));
            } catch (IOException | RuntimeException e) {
                checks.put("browser_bundle_readable", false);
                checks.put("browser_bundle_problem", e.toString());
            }
        }));
        steps.add(new Step("browser_runtime_installed", () -> installJob == null || installJob.finished(), () -> {
            RuntimeManager.BrowserRuntimeInfo info = RuntimeManager.browserInfo();
            checks.put("browser_runtime_installed", installJob != null && installJob.phase == RuntimeInstaller.Phase.DONE
                    && info.status() == RuntimeManager.BrowserStatus.INSTALLED);
            checks.put("browser_runtime", info.status() + " " + info.bundle() + (installJob == null ? "" : " " + installJob.message));
        }));
        steps.add(new Step("youtube_source", () -> true, () -> {
            OteTvClientConfig.GLOBAL_MUTE.set(true);
            OteTvClientConfig.DEBUG_LOGGING.set(true);  // the worker's own log lines go to the client log
            UUID playerId = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                TvSessionService svc = TvSessionService.get();
                TvBlockEntity be = svc.blockEntity(svc.session(tvId));
                try {
                    MediaSource source = SourceInput.fromLink("https://www.youtube.com/watch?v=" + YOUTUBE, "", ServerSourcePolicy.structuralPolicy());
                    TvActions.Outcome set = TvActions.perform(svc, svc.session(tvId), be, player, TvActionType.SET_SOURCE, -1, TvActions.Args.ofSource(source));
                    checks.put("youtube_source_set", set.accepted());
                } catch (SourceException e) {
                    checks.put("youtube_source_set", false);
                    checks.put("youtube_source_problem", e.issue().name());
                }
                // Three blocks in front of the screen: the picture projects above the 320x180 px minimum.
                BlockPos spot = anchor.north(3);
                viewerSpot = spot;
                player.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            });
        }));
        steps.add(new Step("youtube_consent", () -> session() != null && session().state() == LocalPlaybackState.NEEDS_CONSENT, () -> {
            checks.put("youtube_asked_for_consent", true);
            checks.put("youtube_consent_host", session().waitingHost());
            ConsentStore.grant(ServerContext.current(), session().waitingHost(), false);
        }));
        steps.add(new Step("youtube_viewer_in_place", () -> viewerSpot != null && mc.player.blockPosition().equals(viewerSpot), () -> {
            mc.setWindowActive(true);
            aim(mc);
        }));
        steps.add(new Step("youtube_playing", () -> {
            traceYouTube();
            ClientPlaybackSession s = session();
            if (s != null && s.isWaitingForLocalResume()) {
                checks.put("youtube_needed_gesture", true);
                s.localResume();
            }
            return s != null && s.isYouTube() && s.state() == LocalPlaybackState.PLAYING && frames() > 30;
        }, () -> {
            checks.put("youtube_first_picture_ms", Util.getMillis() - START);
            waitUntil = Util.getMillis() + 15000;
        }));
        steps.add(new Step("youtube_sample", () -> {
            traceYouTube();
            return Util.getMillis() >= waitUntil;
        }, () -> {
            checks.put("youtube_trace", new ArrayList<>(youtubeTrace));
            ClientPlaybackSession s = session();
            checks.put("youtube_still_playing", s.state() == LocalPlaybackState.PLAYING);
            checks.put("youtube_frames", frames());
            checks.put("youtube_position_ms", s.positionMs());
            checks.put("youtube_diagnostics", s.diagnostics());
            shot("youtube_world");
        }));
        steps.add(new Step("youtube_viewing", () -> true, () -> {
            mc.options.hideGui = false;
            mc.setScreen(new ViewingScreen(null, tvId));
            shot("youtube_viewing");
        }));
        steps.add(new Step("youtube_done", () -> true, () -> {
            mc.setScreen(null);
            OteTvClientConfig.GLOBAL_MUTE.set(false);
            OteTvClientConfig.DEBUG_LOGGING.set(false);
        }));
    }

    /** Server thread: places a plasma TV six blocks in front of the player through the item, then tunes channel 1. */
    private static void stage(Minecraft mc) {
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        UUID playerId = mc.player.getUUID();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            ServerLevel level = player.serverLevel();
            level.setDayTime(6000);
            player.setYRot(0); // looking south, so the TV faces north toward the player
            BlockPos at = player.blockPosition().south(6);
            ItemStack stack = new ItemStack(ModItems.tv(TvModel.PLASMA));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at.below()).add(0, 0.5, 0), Direction.UP, at.below(), false);
            InteractionResult placed = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            checks.put("placed_through_item", placed.consumesAction());
            if (!(level.getBlockEntity(at) instanceof TvBlockEntity be)) {
                checks.put("controller_present", false);
                return;
            }
            anchor = at;
            tvId = be.tvId();
            TvSessionService svc = TvSessionService.get();
            TvActions.Outcome on = TvActions.perform(svc, svc.session(tvId), be, player, TvActionType.SET_POWER, -1, TvActions.Args.ofInt(1));
            checks.put("powered_on", on.accepted());
            TvActions.Outcome tuned = TvActions.perform(svc, svc.session(tvId), be, player, TvActionType.SELECT_CHANNEL, -1, TvActions.Args.ofInt(1));
            checks.put("tuned_demo_channel", tuned.accepted());
            if (!tuned.accepted()) {
                checks.put("tune_rejected_because", tuned.reason().name());
            }
        });
    }

    private static TvBlockEntity clientTv(Minecraft mc) {
        BlockPos at = anchor;
        return at != null && mc.level.getBlockEntity(at) instanceof TvBlockEntity be ? be : null;
    }

    private static ClientPlaybackSession session() {
        UUID id = tvId;
        return id == null ? null : ClientTvManager.get().session(id);
    }

    private static long frames() {
        ClientPlaybackSession s = session();
        return s == null || s.backend() == null ? 0 : s.backend().frames().received();
    }

    /** Looks at the middle of the picture. */
    private static void aim(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = Vec3.atBottomCenterOf(anchor).add(0, 1.1, 0);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
    }

    /** Records this client's shared-timeline error and whether a caption is showing; always returns true. */
    private static boolean sample() {
        ClientPlaybackSession s = session();
        if (s != null && s.state() == LocalPlaybackState.PLAYING) {
            syncErrors.add(s.positionMs() - s.targetPosition());
            sawCue |= s.activeCue() != null;
        }
        return true;
    }

    private static void report(String phase) {
        if (syncErrors.isEmpty()) {
            checks.put(phase + "_sync_samples", false);
            return;
        }
        List<Long> abs = new ArrayList<>();
        long sum = 0;
        for (long e : syncErrors) {
            abs.add(Math.abs(e));
            sum += Math.abs(e);
        }
        Collections.sort(abs);
        checks.put(phase + "_sync_samples", syncErrors.size());
        checks.put(phase + "_sync_error_mean_ms", sum / abs.size());
        checks.put(phase + "_sync_error_p95_ms", abs.get(Math.min(abs.size() - 1, (int) (abs.size() * 0.95))));
        checks.put(phase + "_sync_error_max_ms", abs.get(abs.size() - 1));
        // The drift controller corrects beyond 150 ms and hard-seeks beyond 500 ms (section 10.4).
        checks.put(phase + "_sync_within_150ms_p95", abs.get(Math.min(abs.size() - 1, (int) (abs.size() * 0.95))) <= 150);
    }

    private static String state() {
        ClientPlaybackSession s = session();
        S2C.ActionResult r = lastResult;
        return (s == null ? " (no session)" : " (state " + s.state() + ", error " + s.error() + " " + s.errorDetail() + ", diagnostics " + s.diagnostics() + ")")
                + (r == null ? "" : " last result " + r.accepted() + " " + r.reason());
    }

    private static void createWorld(Minecraft mc) {
        String name = "OteTvSmokeTest" + System.currentTimeMillis();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261025L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private static void shot(String name) {
        Minecraft.getInstance().getToasts().clear();
        pendingShot = name;
        pendingFrames = 5;
        pendingTicks = 0;
        checks.put("shot_" + name, false);
    }

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent event) {
        if (OUTPUT == null || pendingShot == null || event.phase != TickEvent.Phase.END || --pendingFrames > 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String name = pendingShot;
        pendingShot = null;
        Screenshot.grab(mc.gameDirectory, "otetv_smoke_" + name + ".png", mc.getMainRenderTarget(), msg -> log("screenshot " + name));
        checks.put("shot_" + name, true);
    }

    private static boolean allPassed() {
        synchronized (checks) {
            for (Map.Entry<String, Object> e : checks.entrySet()) {
                if (e.getValue() instanceof Boolean b && !b) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void finish(Minecraft mc, boolean pass, String reason) {
        finished = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pass", pass);
        result.put("reason", reason);
        synchronized (checks) {
            result.put("checks", new LinkedHashMap<>(checks));
        }
        try {
            Files.createDirectories(Path.of(OUTPUT));
            Files.writeString(Path.of(OUTPUT, "smoketest-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            OteTV.LOGGER.error("SMOKETEST could not write its result", e);
        }
        log("finished: pass=" + pass + " (" + reason + ")");
        mc.stop();
    }

    private static void log(String message) {
        OteTV.LOGGER.info("SMOKETEST {}", message);
    }
}
