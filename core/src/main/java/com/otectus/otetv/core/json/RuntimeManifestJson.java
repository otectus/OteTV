package com.otectus.otetv.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;
import com.otectus.otetv.core.runtime.PlatformId;
import com.otectus.otetv.core.runtime.RuntimeBundle;
import com.otectus.otetv.core.runtime.SafeZipExtractor;
import com.otectus.otetv.core.text.TextSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses and validates the runtime bundle manifest. A single invalid bundle invalidates the whole manifest. */
public final class RuntimeManifestJson {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_SIZE = 2L * 1024 * 1024 * 1024;

    private RuntimeManifestJson() {
    }

    public static List<RuntimeBundle> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("manifest must be an object");
        }
        JsonObject o = root.getAsJsonObject();
        if (!o.has("schemaVersion") || o.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported manifest schemaVersion");
        }
        JsonArray bundles = o.has("bundles") ? o.getAsJsonArray("bundles") : new JsonArray();
        if (bundles.size() > 64) {
            throw new IllegalArgumentException("too many bundles");
        }
        List<RuntimeBundle> out = new ArrayList<>();
        for (JsonElement e : bundles) {
            out.add(parseBundle(e.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    private static RuntimeBundle parseBundle(JsonObject b) {
        String id = str(b, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad bundle id");
        }
        String version = str(b, "version");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("bad version for " + id);
        }
        PlatformId platform = new PlatformId(PlatformId.Os.valueOf(str(b, "os").toUpperCase(Locale.ROOT)),
                PlatformId.Arch.valueOf(str(b, "arch").toUpperCase(Locale.ROOT)));
        List<SafeUrl> urls = new ArrayList<>();
        for (JsonElement u : b.getAsJsonArray("urls")) {
            try {
                SafeUrl url = SafeUrl.parse(u.getAsString());
                if (!url.isHttps()) {
                    throw new IllegalArgumentException("bundle URLs must be https");
                }
                urls.add(url);
            } catch (UrlRejectedException ex) {
                throw new IllegalArgumentException("bad bundle URL: " + ex.reason());
            }
        }
        if (urls.isEmpty() || urls.size() > 4) {
            throw new IllegalArgumentException("bundle needs 1..4 URLs");
        }
        String sha = str(b, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) {
            throw new IllegalArgumentException("bad sha256 for " + id);
        }
        long size = b.get("size").getAsLong();
        long unpacked = b.get("unpackedSize").getAsLong();
        if (size <= 0 || size > MAX_SIZE || unpacked <= 0 || unpacked > MAX_SIZE * 2) {
            throw new IllegalArgumentException("bad sizes for " + id);
        }
        JsonObject protocol = b.getAsJsonObject("protocol");
        int pmin = protocol.get("min").getAsInt();
        int pmax = protocol.get("max").getAsInt();
        String entrypoint = str(b, "entrypoint");
        SafeZipExtractor.validateEntryName(entrypoint);
        List<String> executables = new ArrayList<>();
        if (b.has("executables")) {
            for (JsonElement x : b.getAsJsonArray("executables")) {
                String name = x.getAsString();
                SafeZipExtractor.validateEntryName(name);
                executables.add(name);
            }
        }
        List<String> licenses = new ArrayList<>();
        if (b.has("licenses")) {
            for (JsonElement x : b.getAsJsonArray("licenses")) {
                String name = x.getAsString();
                SafeZipExtractor.validateEntryName(name);
                licenses.add(name);
            }
        }
        String minimum = b.has("minimumSystem") ? TextSanitizer.sanitize(b.get("minimumSystem").getAsString(), 128) : "";
        String notice = b.has("notice") ? TextSanitizer.sanitize(b.get("notice").getAsString(), 256) : "";
        return new RuntimeBundle(id, version, platform, List.copyOf(urls), sha, size, unpacked, pmin, pmax, entrypoint,
                List.copyOf(executables), List.copyOf(licenses), minimum, notice);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        String v = e.getAsString();
        if (v.length() > 512) {
            throw new IllegalArgumentException(key + " too long");
        }
        return v;
    }
}
