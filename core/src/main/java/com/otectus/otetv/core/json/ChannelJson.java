package com.otectus.otetv.core.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.text.TextSanitizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated channel definition JSON (section 20.1), e.g.
 *
 * <pre>
 * { "schemaVersion": 1, "id": "otetv:welcome", "number": 1,
 *   "displayName": { "translate": "channel.otetv.welcome" },
 *   "source": { "type": "bundled", "assetId": "otetv:welcome" } }
 * </pre>
 *
 * Unknown fields, unknown source types, out-of-range numbers and oversized text are errors for that entry only.
 * A YouTube definition stores a video id and optional start time; the URL is constructed locally. A shared local
 * asset stores its content identity, never a path.
 */
public final class ChannelJson {
    private static final Set<String> TOP_LEVEL = Set.of("schemaVersion", "id", "number", "displayName", "description", "source", "enabled");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,48}:[a-z0-9_./-]{1,79}");
    private static final Pattern TRANSLATION_KEY = Pattern.compile("[a-zA-Z0-9_.-]{1,128}");

    public sealed interface Result permits Ok, Failed {
    }

    public record Ok(ChannelDefinition definition) implements Result {
    }

    public record Failed(String error) implements Result {
    }

    private ChannelJson() {
    }

    public static Result parse(JsonElement element, MediaSource.Policy policy, ChannelDefinition.Origin origin) {
        try {
            return new Ok(parseOrThrow(element, policy, origin));
        } catch (IllegalArgumentException e) {
            return new Failed(e.getMessage());
        } catch (SourceException e) {
            return new Failed("source: " + e.issue().name().toLowerCase(java.util.Locale.ROOT)
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
        }
    }

    private static ChannelDefinition parseOrThrow(JsonElement element, MediaSource.Policy policy, ChannelDefinition.Origin origin) throws SourceException {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("definition must be a JSON object");
        }
        JsonObject o = element.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!TOP_LEVEL.contains(key)) {
                throw new IllegalArgumentException("unknown field '" + key + "'");
            }
        }
        int schema = intField(o, "schemaVersion", 0);
        if (schema != OteTvCore.CHANNEL_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion " + schema);
        }
        String id = stringField(o, "id", 128);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid id '" + id + "'");
        }
        int number = intField(o, "number", -1);
        if (number < 1 || number > Limits.MAX_CHANNEL_NUMBER) {
            throw new IllegalArgumentException("number must be 1.." + Limits.MAX_CHANNEL_NUMBER);
        }
        String displayName;
        boolean translatable;
        JsonElement dn = o.get("displayName");
        if (dn == null) {
            throw new IllegalArgumentException("missing displayName");
        }
        if (dn.isJsonPrimitive() && dn.getAsJsonPrimitive().isString()) {
            displayName = TextSanitizer.sanitize(dn.getAsString(), Limits.MAX_DISPLAY_NAME);
            translatable = false;
        } else if (dn.isJsonObject() && dn.getAsJsonObject().has("translate") && dn.getAsJsonObject().size() == 1) {
            displayName = dn.getAsJsonObject().get("translate").getAsString();
            if (!TRANSLATION_KEY.matcher(displayName).matches()) {
                throw new IllegalArgumentException("invalid translation key");
            }
            translatable = true;
        } else {
            throw new IllegalArgumentException("displayName must be a string or {\"translate\": key}");
        }
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("empty displayName");
        }
        String description = o.has("description") ? TextSanitizer.sanitize(stringField(o, "description", Limits.MAX_DESCRIPTION * 4), Limits.MAX_DESCRIPTION) : "";
        boolean enabled = !o.has("enabled") || booleanField(o, "enabled");
        JsonElement src = o.get("source");
        if (src == null || !src.isJsonObject()) {
            throw new IllegalArgumentException("missing source object");
        }
        MediaSource source = parseSource(src.getAsJsonObject(), policy);
        return new ChannelDefinition(id, number, displayName, translatable, description, source, enabled, origin);
    }

    /** JSON source object to a validated source. Field names follow the brief: assetId, url, videoId, sha256... */
    public static MediaSource parseSource(JsonObject s, MediaSource.Policy policy) throws SourceException {
        String typeId = stringField(s, "type", 32);
        SourceType type = SourceType.byId(typeId).orElseThrow(() -> new IllegalArgumentException("unknown source type '" + typeId + "'"));
        if (!type.isShareable()) {
            throw new IllegalArgumentException("source type '" + typeId + "' cannot be shared");
        }
        Map<String, String> fields = new HashMap<>();
        Map<String, String> rename = Map.of("assetId", MediaSource.F_ASSET, "url", MediaSource.F_URL, "captionsUrl", MediaSource.F_CAPTIONS,
                "videoId", MediaSource.F_VIDEO_ID, "startSeconds", MediaSource.F_START, "sha256", MediaSource.F_SHA256,
                "durationMs", MediaSource.F_DURATION, "label", MediaSource.F_LABEL);
        for (Map.Entry<String, JsonElement> e : s.entrySet()) {
            if (e.getKey().equals("type")) {
                continue;
            }
            String mapped = rename.get(e.getKey());
            if (mapped == null) {
                throw new IllegalArgumentException("unknown source field '" + e.getKey() + "'");
            }
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive()) {
                throw new IllegalArgumentException("source field '" + e.getKey() + "' must be a primitive");
            }
            JsonPrimitive p = v.getAsJsonPrimitive();
            fields.put(mapped, p.isNumber() ? Long.toString(p.getAsLong()) : p.getAsString());
        }
        return MediaSource.fromFields(type, fields, policy);
    }

    /** The inverse of {@link #parseSource}, used for the admin channel store and example datapacks. */
    public static JsonObject writeSource(MediaSource source) {
        JsonObject o = new JsonObject();
        o.addProperty("type", source.type().id());
        Map<String, String> reverse = Map.of(MediaSource.F_ASSET, "assetId", MediaSource.F_URL, "url", MediaSource.F_CAPTIONS, "captionsUrl",
                MediaSource.F_VIDEO_ID, "videoId", MediaSource.F_START, "startSeconds", MediaSource.F_SHA256, "sha256",
                MediaSource.F_DURATION, "durationMs", MediaSource.F_LABEL, "label");
        for (Map.Entry<String, String> e : source.toFields().entrySet()) {
            String key = reverse.getOrDefault(e.getKey(), e.getKey());
            if (key.equals("startSeconds") || key.equals("durationMs")) {
                o.addProperty(key, Long.parseLong(e.getValue()));
            } else {
                o.addProperty(key, e.getValue());
            }
        }
        return o;
    }

    public static JsonObject write(ChannelDefinition def) {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", OteTvCore.CHANNEL_SCHEMA_VERSION);
        o.addProperty("id", def.id());
        o.addProperty("number", def.number());
        if (def.translatable()) {
            JsonObject t = new JsonObject();
            t.addProperty("translate", def.displayName());
            o.add("displayName", t);
        } else {
            o.addProperty("displayName", def.displayName());
        }
        if (!def.description().isEmpty()) {
            o.addProperty("description", def.description());
        }
        o.add("source", writeSource(def.source()));
        if (!def.enabled()) {
            o.addProperty("enabled", false);
        }
        return o;
    }

    private static String stringField(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("'" + key + "' must be a string");
        }
        String v = e.getAsString();
        if (v.length() > max) {
            throw new IllegalArgumentException("'" + key + "' is too long");
        }
        return v;
    }

    private static int intField(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null) {
            return fallback;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + key + "' must be a number");
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }
        return (int) d;
    }

    private static boolean booleanField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }
        return e.getAsBoolean();
    }

    /** Validates a whole catalog: duplicate ids and numbers fail individually, keeping the first occurrence. */
    public static List<String> findDuplicates(List<ChannelDefinition> definitions) {
        Map<String, String> ids = new HashMap<>();
        Map<Integer, String> numbers = new HashMap<>();
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        for (ChannelDefinition d : definitions) {
            if (ids.putIfAbsent(d.id(), d.id()) != null) {
                problems.add("duplicate id " + d.id());
            }
            if (numbers.putIfAbsent(d.number(), d.id()) != null) {
                problems.add("duplicate number " + d.number() + " (" + d.id() + " conflicts with " + numbers.get(d.number()) + ")");
            }
        }
        return problems;
    }
}
