package com.otectus.otetv.client.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.runtime.Hashing;
import com.otectus.otetv.core.text.TextSanitizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicitly granted local media files (section 11.4). A file becomes playable only by a deliberate player action:
 * placing it in the OteTV media folder or importing it. Grants are resolved to real paths (symlinks followed) and
 * re-checked before each use. Paths never leave this client; shared assets are matched by SHA-256 content identity.
 */
public final class LocalMediaLibrary {
    private static final String FILE = "library.json";
    public static final Set<String> EXTENSIONS = Set.of("webm", "mkv", "mp4", "m4v", "mov", "ogv", "ogg", "opus", "oga");
    private static final long MAX_SIZE = 16L * 1024 * 1024 * 1024;

    public record Entry(String key, Path realPath, long size, long lastModified, String label, String sha256) {
        public boolean hasDigest() {
            return sha256 != null && !sha256.isEmpty();
        }
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static boolean loaded;

    private LocalMediaLibrary() {
    }

    public static Path mediaFolder() {
        return LocalStore.dir("media");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        LocalStore.read(FILE).ifPresent(json -> {
            if (!json.isJsonObject() || !json.getAsJsonObject().has("files")) {
                return;
            }
            for (JsonElement e : json.getAsJsonObject().getAsJsonArray("files")) {
                try {
                    JsonObject o = e.getAsJsonObject();
                    Path p = Path.of(o.get("path").getAsString());
                    Entry entry = new Entry(o.get("key").getAsString(), p, o.get("size").getAsLong(), o.get("modified").getAsLong(),
                            TextSanitizer.sanitize(o.get("label").getAsString(), 96), o.has("sha256") ? o.get("sha256").getAsString() : "");
                    if (entry.key().matches("[0-9a-f]{16,64}")) {
                        ENTRIES.put(entry.key(), entry);
                    }
                } catch (RuntimeException ignored) {
                    // skip malformed entries
                }
            }
        });
    }

    private static void save() {
        JsonArray files = new JsonArray();
        for (Entry e : ENTRIES.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", e.key());
            o.addProperty("path", e.realPath().toString());
            o.addProperty("size", e.size());
            o.addProperty("modified", e.lastModified());
            o.addProperty("label", e.label());
            if (e.hasDigest()) {
                o.addProperty("sha256", e.sha256());
            }
            files.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("files", files);
        LocalStore.write(FILE, root);
    }

    /** Lists granted files plus any file the player placed in the media folder (itself an explicit action). */
    public static List<Entry> list() {
        ensureLoaded();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mediaFolder())) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && EXTENSIONS.contains(extension(p))) {
                    grant(p);
                }
            }
        } catch (IOException e) {
            OteTV.LOGGER.warn("Could not list the OteTV media folder: {}", e.getMessage());
        }
        List<Entry> list = new ArrayList<>(ENTRIES.values());
        list.removeIf(e -> !Files.isRegularFile(e.realPath()));
        list.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return list;
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Grants a file chosen by the player. Returns the entry, reusing an existing grant for the same real path. */
    public static Optional<Entry> grant(Path chosen) {
        ensureLoaded();
        try {
            Path real = chosen.toRealPath();
            if (!Files.isRegularFile(real)) {
                return Optional.empty();
            }
            long size = Files.size(real);
            if (size <= 0 || size > MAX_SIZE) {
                return Optional.empty();
            }
            long modified = Files.getLastModifiedTime(real).toMillis();
            for (Entry e : ENTRIES.values()) {
                if (e.realPath().equals(real)) {
                    if (e.size() != size || e.lastModified() != modified) {
                        Entry updated = new Entry(e.key(), real, size, modified, e.label(), "");
                        ENTRIES.put(e.key(), updated);
                        save();
                        return Optional.of(updated);
                    }
                    return Optional.of(e);
                }
            }
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            Entry entry = new Entry(HexFormat.of().formatHex(random), real, size, modified,
                    TextSanitizer.sanitize(real.getFileName().toString(), 96), "");
            ENTRIES.put(entry.key(), entry);
            save();
            return Optional.of(entry);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<Entry> byKey(String key) {
        ensureLoaded();
        return Optional.ofNullable(ENTRIES.get(key));
    }

    /** A granted file whose content matches a shared asset digest. */
    public static Optional<Entry> byDigest(String sha256) {
        ensureLoaded();
        return ENTRIES.values().stream().filter(e -> sha256.equals(e.sha256()) && Files.isRegularFile(e.realPath())).findFirst();
    }

    /**
     * Re-validates a grant immediately before use: same real path, still a regular file, unchanged size and
     * modification time. A changed file must be re-identified.
     */
    public static Optional<Path> resolveForPlayback(Entry entry) {
        try {
            Path real = entry.realPath().toRealPath();
            if (!real.equals(entry.realPath()) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            if (Files.size(real) != entry.size() || Files.getLastModifiedTime(real).toMillis() != entry.lastModified()) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Computes and records the SHA-256 identity off the render thread. */
    public static CompletableFuture<Entry> identify(Entry entry) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String digest = Hashing.sha256Hex(entry.realPath());
                Entry updated = new Entry(entry.key(), entry.realPath(), entry.size(), entry.lastModified(), entry.label(), digest);
                ENTRIES.put(entry.key(), updated);
                save();
                return updated;
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    public static void forget(String key) {
        ensureLoaded();
        if (ENTRIES.remove(key) != null) {
            save();
        }
    }
}
