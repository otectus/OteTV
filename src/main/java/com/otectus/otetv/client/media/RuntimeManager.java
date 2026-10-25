package com.otectus.otetv.client.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.client.store.LocalStore;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.capability.CodecMatrix;
import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.json.RuntimeManifestJson;
import com.otectus.otetv.core.runtime.Hashing;
import com.otectus.otetv.core.runtime.PlatformId;
import com.otectus.otetv.core.runtime.RuntimeBundle;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side runtime management (section 17): verified extraction of the embedded media worker, detection of the
 * GStreamer runtime, the native browser runtime bundle, and launch specifications built from argument arrays and
 * absolute paths. Nothing here ever runs on a dedicated server, and nothing is downloaded or launched without the
 * player asking for playback or installation.
 */
public final class RuntimeManager {
    public enum MediaStatus { UNKNOWN, PROBING, AVAILABLE, MISSING, INCOMPATIBLE, ERROR }

    public record MediaRuntimeInfo(MediaStatus status, String runtimeVersion, Set<String> elements, List<CodecMatrix.Result> codecs,
                                   boolean rateControl, String detail) {
        public static final MediaRuntimeInfo UNKNOWN = new MediaRuntimeInfo(MediaStatus.UNKNOWN, "", Set.of(), List.of(), false, "");
    }

    public enum BrowserStatus { NOT_INSTALLED, INSTALLED, UNSUPPORTED_PLATFORM }

    public record BrowserRuntimeInfo(BrowserStatus status, String bundle, @Nullable Path executable, String detail) {
    }

    public record LaunchSpec(List<String> command, Map<String, String> environment, Path workDir) {
    }

    private static final AtomicReference<CompletableFuture<MediaRuntimeInfo>> PROBE = new AtomicReference<>();
    private static volatile MediaRuntimeInfo lastMedia = MediaRuntimeInfo.UNKNOWN;

    private RuntimeManager() {
    }

    public static Path runtimeDir() {
        return LocalStore.dir("runtime");
    }

    public static PlatformId platform() {
        return PlatformId.current();
    }

    /** The running JVM's own executable: an absolute, already-trusted path (never PATH lookup). */
    public static Path javaExecutable() {
        Optional<String> cmd = ProcessHandle.current().info().command();
        if (cmd.isPresent() && Files.isExecutable(Path.of(cmd.get()))) {
            return Path.of(cmd.get()).toAbsolutePath();
        }
        String exe = platform().os() == PlatformId.Os.WINDOWS ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toAbsolutePath();
    }

    // ---- media worker -----------------------------------------------------------------------------------------------

    private static Properties workerProperties() throws IOException {
        try (InputStream in = RuntimeManager.class.getResourceAsStream("/otetv_workers/workers.properties")) {
            if (in == null) {
                throw new IOException("media worker is missing from this OteTV build");
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    /**
     * Extracts the embedded worker jar if needed and verifies its SHA-256 before every launch. A mismatch (tampering
     * or a partial write) triggers re-extraction from the mod jar, which is the trust anchor.
     */
    public static synchronized Path mediaWorkerJar() throws IOException {
        Properties p = workerProperties();
        String expected = p.getProperty("media.sha256", "");
        String version = p.getProperty("media.version", "dev").replaceAll("[^A-Za-z0-9._-]", "");
        Path dir = LocalStore.dir("runtime").resolve("workers");
        LocalStore.ensureDirectory(dir);
        Path jar = dir.resolve("otetv-media-worker-" + version + ".jar");
        if (Files.isRegularFile(jar) && Hashing.matches(expected, Hashing.sha256Hex(jar))) {
            return jar;
        }
        Path temp = dir.resolve(jar.getFileName() + ".tmp");
        try (InputStream in = RuntimeManager.class.getResourceAsStream("/otetv_workers/" + p.getProperty("media.file", "otetv-media-worker.jar"))) {
            if (in == null) {
                throw new IOException("media worker jar is missing from this OteTV build");
            }
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Hashing.matches(expected, Hashing.sha256Hex(temp))) {
            Files.deleteIfExists(temp);
            throw new IOException("embedded media worker failed verification");
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    @Nullable
    private static Path windowsGStreamerBin() {
        for (String var : List.of("GSTREAMER_1_0_ROOT_MSVC_X86_64", "GSTREAMER_1_0_ROOT_X86_64")) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                Path bin = Path.of(v).resolve("bin");
                if (Files.isDirectory(bin)) {
                    return bin.toAbsolutePath();
                }
            }
        }
        for (String candidate : List.of("C:\\Program Files\\gstreamer\\1.0\\msvc_x86_64", "C:\\gstreamer\\1.0\\msvc_x86_64")) {
            Path bin = Path.of(candidate, "bin");
            if (Files.isDirectory(bin)) {
                return bin;
            }
        }
        return null;
    }

    /** A project GStreamer bundle installed through the runtime installer, if any. */
    @Nullable
    private static Path bundledGStreamer() {
        return activeBundle("gstreamer").map(b -> b.resolve("gstreamer")).filter(Files::isDirectory).orElse(null);
    }

    public static LaunchSpec mediaLaunchSpec() throws IOException {
        Path jar = mediaWorkerJar();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable().toString());
        cmd.add("-Xms32m");
        cmd.add("-Xmx512m");
        cmd.add("-XX:+UseSerialGC");
        cmd.add("-Djava.awt.headless=true");
        Map<String, String> env = new HashMap<>();
        Path registry = runtimeDir().resolve("gst-registry-" + platform().id() + ".bin");
        env.put("GST_REGISTRY_1_0", registry.toString());
        env.put("GST_REGISTRY_FORK", "no");
        env.put("GST_DEBUG", OteTvClientConfig.DEBUG_LOGGING.get() ? "2" : "1");
        env.put("GST_DEBUG_NO_COLOR", "1");
        // Adaptive demuxers and network sources would fetch URLs on their own; every byte must go through the worker's
        // guarded fetcher instead (section 11.3).
        env.put("GST_PLUGIN_FEATURE_RANK", "hlsdemux:NONE,hlsdemux2:NONE,dashdemux:NONE,dashdemux2:NONE,mssdemux:NONE,mssdemux2:NONE,"
                + "souphttpsrc:NONE,curlhttpsrc:NONE,rtspsrc:NONE,rtmpsrc:NONE,rtmp2src:NONE,giosrc:NONE,smbsrc:NONE,ftpsrc:NONE");
        Path bundled = bundledGStreamer();
        if (bundled != null) {
            Path lib = bundled.resolve(platform().os() == PlatformId.Os.WINDOWS ? "bin" : "lib");
            env.put("GST_PLUGIN_SYSTEM_PATH_1_0", bundled.resolve("lib").resolve("gstreamer-1.0").toString());
            env.put("GST_PLUGIN_PATH_1_0", "");
            Path scanner = bundled.resolve("libexec").resolve("gstreamer-1.0").resolve(platform().os() == PlatformId.Os.WINDOWS ? "gst-plugin-scanner.exe" : "gst-plugin-scanner");
            if (Files.isExecutable(scanner)) {
                env.put("GST_PLUGIN_SCANNER_1_0", scanner.toString());
            }
            cmd.add("-Djna.library.path=" + lib);
            if (platform().os() == PlatformId.Os.WINDOWS) {
                env.put("PATH", lib + ";" + System.getenv().getOrDefault("PATH", ""));
            } else {
                env.put("LD_LIBRARY_PATH", lib.toString());
            }
        } else if (platform().os() == PlatformId.Os.WINDOWS) {
            Path bin = windowsGStreamerBin();
            if (bin != null) {
                cmd.add("-Djna.library.path=" + bin);
                env.put("PATH", bin + ";" + System.getenv().getOrDefault("PATH", ""));
            }
        }
        cmd.add("-jar");
        cmd.add(jar.toString());
        cmd.add("--parent-pid");
        cmd.add(Long.toString(ProcessHandle.current().pid()));
        Path work = LocalStore.dir("runtime").resolve("work");
        LocalStore.ensureDirectory(work);
        return new LaunchSpec(List.copyOf(cmd), env, work);
    }

    public static MediaRuntimeInfo mediaInfo() {
        return lastMedia;
    }

    /** Starts (once) or returns the cached runtime probe. */
    public static CompletableFuture<MediaRuntimeInfo> probeMedia() {
        CompletableFuture<MediaRuntimeInfo> existing = PROBE.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<MediaRuntimeInfo> future = CompletableFuture.supplyAsync(RuntimeManager::runProbe);
        if (!PROBE.compareAndSet(null, future)) {
            return PROBE.get();
        }
        lastMedia = new MediaRuntimeInfo(MediaStatus.PROBING, "", Set.of(), List.of(), false, "");
        future.thenAccept(info -> lastMedia = info);
        return future;
    }

    public static void invalidateProbe() {
        PROBE.set(null);
        lastMedia = MediaRuntimeInfo.UNKNOWN;
    }

    private static MediaRuntimeInfo runProbe() {
        LaunchSpec spec;
        try {
            spec = mediaLaunchSpec();
        } catch (IOException e) {
            return new MediaRuntimeInfo(MediaStatus.ERROR, "", Set.of(), List.of(), false, e.getMessage());
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<MediaRuntimeInfo> result = new AtomicReference<>();
        WorkerProcess.Listener listener = new WorkerProcess.Listener() {
            @Override
            public void onMessage(IpcType type, PayloadReader payload) throws IOException {
                if (type == IpcType.HELLO_ACK) {
                    Msg.HelloAck ack = Msg.HelloAck.read(payload);
                    Set<String> elements = new TreeSet<>(ack.elements());
                    MediaStatus status = ack.protocol() == OteTvCore.IPC_PROTOCOL_VERSION ? MediaStatus.AVAILABLE : MediaStatus.INCOMPATIBLE;
                    result.set(new MediaRuntimeInfo(status, ack.runtimeVersion(), elements, CodecMatrix.evaluate(elements),
                            (ack.flags() & Msg.HelloAck.FLAG_RATE_CONTROL) != 0, ""));
                    done.countDown();
                } else if (type == IpcType.ERROR) {
                    Msg.Error e = Msg.Error.read(payload);
                    result.set(new MediaRuntimeInfo(MediaStatus.MISSING, "", Set.of(), List.of(), false, e.detail()));
                    done.countDown();
                }
            }

            @Override
            public void onVideoFrame(FrameHeader header, IpcReader reader, int pixelBytes) throws IOException {
                reader.skip(pixelBytes);
            }

            @Override
            public void onExit(int exitCode, boolean requested, String reason) {
                result.compareAndSet(null, new MediaRuntimeInfo(MediaStatus.MISSING, "", Set.of(), List.of(), false,
                        "worker exited (" + exitCode + "): " + reason));
                done.countDown();
            }
        };
        WorkerProcess worker = null;
        try {
            worker = WorkerProcess.launch("media probe", spec.command(), spec.environment(), spec.workDir(), listener, true);
            worker.send(IpcType.HELLO, new Msg.Hello(OteTvCore.IPC_PROTOCOL_VERSION, OteTV.MOD_ID, Msg.ROLE_MEDIA, false).write());
            if (!done.await(20, TimeUnit.SECONDS)) {
                result.compareAndSet(null, new MediaRuntimeInfo(MediaStatus.ERROR, "", Set.of(), List.of(), false, "probe timed out"));
            }
            List<String> log = worker.recentLog();
            MediaRuntimeInfo info = result.get();
            if (info.status() == MediaStatus.MISSING && info.detail().isEmpty() && !log.isEmpty()) {
                info = new MediaRuntimeInfo(MediaStatus.MISSING, "", Set.of(), List.of(), false, log.get(log.size() - 1));
            }
            OteTV.LOGGER.info("OteTV media runtime: {} {}", info.status(), info.runtimeVersion());
            return info;
        } catch (IOException e) {
            return new MediaRuntimeInfo(MediaStatus.ERROR, "", Set.of(), List.of(), false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MediaRuntimeInfo(MediaStatus.ERROR, "", Set.of(), List.of(), false, "interrupted");
        } finally {
            if (worker != null) {
                worker.close();
            }
        }
    }

    // ---- bundled media ------------------------------------------------------------------------------------------------

    /** Extracts a bundled asset that ships inside the mod jar (not resource-pack overridable) and returns its path. */
    public static synchronized Path bundledAsset(String assetId) throws IOException {
        if (!"otetv:welcome".equals(assetId)) {
            throw new IOException("unknown bundled asset " + assetId);
        }
        String name = welcomeEncoding(lastMedia.elements());
        Path dir = LocalStore.dir("runtime").resolve("media");
        LocalStore.ensureDirectory(dir);
        Path out = dir.resolve(name);
        try (InputStream in = RuntimeManager.class.getResourceAsStream("/otetv_media/" + name)) {
            if (in == null) {
                throw new IOException("bundled demo is missing from this OteTV build");
            }
            byte[] data = in.readAllBytes();
            if (!Files.isRegularFile(out) || Files.size(out) != data.length || !Hashing.matches(Hashing.sha256Hex(data), Hashing.sha256Hex(out))) {
                Path temp = dir.resolve(name + ".tmp");
                Files.write(temp, data);
                Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return out.toRealPath();
    }

    /**
     * The demo ships as VP8/Vorbis WebM (the brief's baseline) plus a Theora/Vorbis Ogg encode of the same original clip,
     * because a runtime with only GStreamer's base plugins has no Matroska demuxer. Every client picks the encoding its
     * runtime decodes; both carry the same timeline, so a shared TV stays in sync across them.
     */
    static String welcomeEncoding(Set<String> elements) {
        if (!CodecMatrix.supports("webm_vp8_vorbis", elements) && CodecMatrix.supports("ogg_theora_vorbis", elements)) {
            return "welcome.ogv";
        }
        return "welcome.webm";
    }

    public static byte[] bundledCaptions(String assetId) throws IOException {
        try (InputStream in = RuntimeManager.class.getResourceAsStream("/otetv_media/welcome.vtt")) {
            return in == null ? new byte[0] : in.readAllBytes();
        }
    }

    // ---- browser runtime ------------------------------------------------------------------------------------------------

    private static Path bundlesDir(String kind) {
        Path d = runtimeDir().resolve(kind);
        LocalStore.ensureDirectory(d);
        return d;
    }

    /** The active bundle directory of a kind ("browser" or "gstreamer"), from its active.json pointer. */
    public static Optional<Path> activeBundle(String kind) {
        Path pointer = bundlesDir(kind).resolve("active.json");
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(pointer, StandardCharsets.UTF_8)).getAsJsonObject();
            String name = o.get("bundle").getAsString();
            if (!name.matches("[a-z0-9][a-z0-9.+_-]{0,100}")) {
                return Optional.empty();
            }
            Path dir = bundlesDir(kind).resolve(name);
            return Files.isRegularFile(dir.resolve(RuntimeInstaller.MARKER)) ? Optional.of(dir) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static BrowserRuntimeInfo browserInfo() {
        if (!platform().isLaunchPlatform()) {
            return new BrowserRuntimeInfo(BrowserStatus.UNSUPPORTED_PLATFORM, "", null, platform().id());
        }
        Optional<Path> dir = activeBundle("browser");
        if (dir.isEmpty()) {
            return new BrowserRuntimeInfo(BrowserStatus.NOT_INSTALLED, "", null, "");
        }
        Path marker = dir.get().resolve(RuntimeInstaller.MARKER);
        try {
            JsonObject o = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
            String entry = o.get("entrypoint").getAsString();
            Path exe = dir.get().resolve(entry).normalize();
            if (!exe.startsWith(dir.get()) || !Files.isRegularFile(exe)) {
                return new BrowserRuntimeInfo(BrowserStatus.NOT_INSTALLED, dir.get().getFileName().toString(), null, "entrypoint missing");
            }
            return new BrowserRuntimeInfo(BrowserStatus.INSTALLED, dir.get().getFileName().toString(), exe.toAbsolutePath(), "");
        } catch (IOException | RuntimeException e) {
            return new BrowserRuntimeInfo(BrowserStatus.NOT_INSTALLED, "", null, "unreadable bundle marker");
        }
    }

    public static LaunchSpec browserLaunchSpec() throws IOException {
        BrowserRuntimeInfo info = browserInfo();
        if (info.status() != BrowserStatus.INSTALLED || info.executable() == null) {
            throw new IOException("browser runtime not installed");
        }
        Path exe = info.executable();
        Path cache = LocalStore.dir("runtime").resolve("browser-cache");
        LocalStore.ensureDirectory(cache);
        List<String> cmd = List.of(exe.toString(),
                "--otetv-parent-pid=" + ProcessHandle.current().pid(),
                "--otetv-app-id=" + OteTvCore.APP_ID,
                "--otetv-cache=" + cache);
        Map<String, String> env = new HashMap<>();
        if (platform().os() == PlatformId.Os.LINUX) {
            env.put("LD_LIBRARY_PATH", exe.getParent().toString());
        }
        return new LaunchSpec(cmd, env, exe.getParent());
    }

    // ---- manifests ------------------------------------------------------------------------------------------------------

    /** Bundles from the built-in manifest (inside the mod jar) and, if configured, the player's own manifest file. */
    public static List<RuntimeBundle> availableBundles() {
        List<RuntimeBundle> out = new ArrayList<>();
        try (InputStream in = RuntimeManager.class.getResourceAsStream("/otetv_runtime/manifest.json")) {
            if (in != null) {
                out.addAll(RuntimeManifestJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException | RuntimeException e) {
            OteTV.LOGGER.warn("Built-in runtime manifest is unreadable: {}", e.getMessage());
        }
        String configured = OteTvClientConfig.RUNTIME_MANIFEST.get().trim();
        if (!configured.isEmpty() && !configured.toLowerCase(Locale.ROOT).startsWith("http")) {
            try {
                Path p = Path.of(configured);
                if (Files.isRegularFile(p) && Files.size(p) < 1024 * 1024) {
                    out.addAll(RuntimeManifestJson.parse(Files.readString(p, StandardCharsets.UTF_8)));
                }
            } catch (IOException | RuntimeException e) {
                OteTV.LOGGER.warn("Configured runtime manifest is unreadable: {}", e.getMessage());
            }
        }
        PlatformId here = platform();
        out.removeIf(b -> !b.platform().equals(here));
        return out;
    }
}
