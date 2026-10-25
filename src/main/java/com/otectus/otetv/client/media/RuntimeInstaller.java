package com.otectus.otetv.client.media;

import com.google.gson.JsonObject;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.net.CancellationToken;
import com.otectus.otetv.core.net.DestinationPolicy;
import com.otectus.otetv.core.net.HttpResponse;
import com.otectus.otetv.core.net.SafeHttpClient;
import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.runtime.Hashing;
import com.otectus.otetv.core.runtime.RuntimeBundle;
import com.otectus.otetv.core.runtime.SafeZipExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * Explicit, verified runtime installation (section 17.2): download off the render thread with progress, cancellation
 * and bounded retries; disk-space check; streaming SHA-256 verification; extraction into a staging directory with
 * traversal and bomb defenses; atomic activation with one rollback copy; uninstall that only deletes directories
 * carrying this installer's marker. No root access, shell scripts or antivirus changes are ever required.
 */
public final class RuntimeInstaller {
    public static final String MARKER = ".otetv-bundle.json";
    private static final int ATTEMPTS = 3;

    public enum Phase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, ACTIVATING, DONE, FAILED, CANCELLED }

    public static final class Job {
        public volatile Phase phase = Phase.IDLE;
        public volatile long done;
        public volatile long total;
        public volatile String message = "";
        final CancellationToken token = new CancellationToken();
        volatile CompletableFuture<Void> future;

        public void cancel() {
            token.cancel();
        }

        public boolean finished() {
            return phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED;
        }
    }

    private RuntimeInstaller() {
    }

    private static String kindOf(RuntimeBundle bundle) {
        return bundle.id().contains("gstreamer") ? "gstreamer" : "browser";
    }

    public static Job download(RuntimeBundle bundle) {
        Job job = new Job();
        job.total = bundle.size();
        job.future = CompletableFuture.runAsync(() -> {
            Path staging = null;
            try {
                Path kindDir = RuntimeManager.runtimeDir().resolve(kindOf(bundle));
                Files.createDirectories(kindDir);
                long needed = bundle.size() + bundle.unpackedSize() + 64L * 1024 * 1024;
                if (Files.getFileStore(kindDir).getUsableSpace() < needed) {
                    throw new IOException("not enough disk space (needs about " + needed / (1024 * 1024) + " MiB)");
                }
                staging = kindDir.resolve(bundle.directoryName() + ".download");
                IOException last = null;
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    try {
                        fetch(bundle, staging, job);
                        last = null;
                        break;
                    } catch (IOException e) {
                        last = e;
                        job.token.throwIfCancelled();
                        Thread.sleep(1000L << attempt);
                    }
                }
                if (last != null) {
                    throw last;
                }
                install(bundle, staging, job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.phase = Phase.CANCELLED;
            } catch (IOException | RuntimeException e) {
                job.phase = job.token.isCancelled() ? Phase.CANCELLED : Phase.FAILED;
                job.message = e.getMessage() == null ? e.toString() : e.getMessage();
                OteTV.LOGGER.warn("Runtime installation of {} failed: {}", bundle.directoryName(), job.message);
            } finally {
                if (staging != null) {
                    try {
                        Files.deleteIfExists(staging);
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        });
        return job;
    }

    private static void fetch(RuntimeBundle bundle, Path target, Job job) throws IOException {
        job.phase = Phase.DOWNLOADING;
        job.done = 0;
        IOException last = null;
        for (SafeUrl url : bundle.urls()) {
            SafeHttpClient client = SafeHttpClient.create(DestinationPolicy.publicOnly(), "OteTV-runtime-installer");
            try (HttpResponse r = client.execute(new SafeHttpClient.Request(url).maxBodyBytes(bundle.size()).timeouts(15_000, 30_000), job.token)) {
                if (r.status() != 200) {
                    throw new IOException("HTTP " + r.status());
                }
                MessageDigest digest = Hashing.sha256();
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        job.done += n;
                    }
                }
                job.phase = Phase.VERIFYING;
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!Hashing.matches(bundle.sha256(), actual)) {
                    throw new IOException("checksum mismatch: the download was corrupted or altered");
                }
                return;
            } catch (com.otectus.otetv.core.net.DestinationBlockedException e) {
                last = new IOException("download host blocked: " + e.describe());
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no download location") : last;
    }

    /** Installs a bundle archive the player already has (manual installation path). */
    public static Job installFromFile(RuntimeBundle bundle, Path zip) {
        Job job = new Job();
        job.future = CompletableFuture.runAsync(() -> {
            try {
                job.phase = Phase.VERIFYING;
                if (Files.size(zip) != bundle.size() || !Hashing.matches(bundle.sha256(), Hashing.sha256Hex(zip))) {
                    throw new IOException("the archive does not match the manifest checksum");
                }
                install(bundle, zip, job);
            } catch (IOException | RuntimeException e) {
                job.phase = Phase.FAILED;
                job.message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
        return job;
    }

    private static void install(RuntimeBundle bundle, Path verifiedZip, Job job) throws IOException {
        Path kindDir = RuntimeManager.runtimeDir().resolve(kindOf(bundle));
        Path stagingDir = kindDir.resolve(bundle.directoryName() + ".staging");
        deleteMarkedTree(stagingDir, true);
        job.phase = Phase.EXTRACTING;
        SafeZipExtractor.extract(verifiedZip, stagingDir, bundle.unpackedSize() + 1024, bundle.executables());
        Path entry = stagingDir.resolve(bundle.entrypoint()).normalize();
        if (!entry.startsWith(stagingDir) || !Files.isRegularFile(entry)) {
            throw new IOException("bundle entrypoint is missing");
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("id", bundle.id());
        marker.addProperty("version", bundle.version());
        marker.addProperty("sha256", bundle.sha256());
        marker.addProperty("entrypoint", bundle.entrypoint());
        Files.writeString(stagingDir.resolve(MARKER), marker.toString(), StandardCharsets.UTF_8);
        job.phase = Phase.ACTIVATING;
        Path finalDir = kindDir.resolve(bundle.directoryName());
        deleteMarkedTree(finalDir, false);
        moveAtomically(stagingDir, finalDir);
        String previous = RuntimeManager.activeBundle(kindOf(bundle)).map(p -> p.getFileName().toString()).orElse("");
        writePointer(kindDir, bundle.directoryName(), previous.equals(bundle.directoryName()) ? "" : previous);
        pruneOld(kindDir, bundle.directoryName(), previous);
        job.phase = Phase.DONE;
        job.message = bundle.directoryName();
        if (kindOf(bundle).equals("gstreamer")) {
            RuntimeManager.invalidateProbe();
        }
    }

    private static void writePointer(Path kindDir, String active, String previous) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("bundle", active);
        o.addProperty("previous", previous);
        Path temp = kindDir.resolve("active.json.tmp");
        Files.writeString(temp, o.toString(), StandardCharsets.UTF_8);
        moveAtomically(temp, kindDir.resolve("active.json"));
    }

    /** Swaps the active bundle with the retained previous one. Running workers keep their files until they exit. */
    public static boolean rollback(String kind) throws IOException {
        Path kindDir = RuntimeManager.runtimeDir().resolve(kind);
        Path pointer = kindDir.resolve("active.json");
        if (!Files.isRegularFile(pointer)) {
            return false;
        }
        JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(pointer, StandardCharsets.UTF_8)).getAsJsonObject();
        String previous = o.has("previous") ? o.get("previous").getAsString() : "";
        if (previous.isEmpty() || !Files.isRegularFile(kindDir.resolve(previous).resolve(MARKER))) {
            return false;
        }
        writePointer(kindDir, previous, o.get("bundle").getAsString());
        return true;
    }

    public static void uninstall(String kind) throws IOException {
        Path kindDir = RuntimeManager.runtimeDir().resolve(kind);
        Files.deleteIfExists(kindDir.resolve("active.json"));
        if (Files.isDirectory(kindDir)) {
            try (var stream = Files.list(kindDir)) {
                for (Path p : stream.toList()) {
                    if (Files.isDirectory(p)) {
                        deleteMarkedTree(p, false);
                    }
                }
            }
        }
    }

    private static void pruneOld(Path kindDir, String keepA, String keepB) throws IOException {
        try (var stream = Files.list(kindDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && !name.equals(keepA) && !name.equals(keepB)) {
                    deleteMarkedTree(p, false);
                }
            }
        }
    }

    /**
     * Deletes a directory tree only if it carries this installer's marker (or is an unfinished staging directory we
     * created), never following symbolic links out of it.
     */
    static void deleteMarkedTree(Path dir, boolean staging) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        if (!staging && !Files.isRegularFile(dir.resolve(MARKER))) {
            OteTV.LOGGER.warn("Refusing to delete {}: not an OteTV runtime bundle", dir.getFileName());
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
