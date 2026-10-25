package com.otectus.otetv.client.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.net.LanGrant;
import com.otectus.otetv.core.text.TextSanitizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local, scoped trust decisions (sections 7.1 and 16): which hosts or services this player agreed to contact on which
 * server, plus deliberate LAN grants. A server can narrow sources but can never create or imply any of these.
 * Remembered grants are visible and revocable; "allow once" grants live only until the game closes.
 */
public final class ConsentStore {
    private static final String FILE = "trust.json";

    public record Grant(String context, String target, long grantedAtMillis) {
    }

    private static final List<Grant> REMEMBERED = new CopyOnWriteArrayList<>();
    private static final Set<String> SESSION_ONLY = java.util.Collections.synchronizedSet(new HashSet<>());
    private static final List<LanGrant> LAN = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean loaded;

    private ConsentStore() {
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        LocalStore.read(FILE).ifPresent(json -> {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject o = json.getAsJsonObject();
            if (o.has("grants")) {
                for (JsonElement e : o.getAsJsonArray("grants")) {
                    JsonObject g = e.getAsJsonObject();
                    REMEMBERED.add(new Grant(TextSanitizer.sanitize(g.get("context").getAsString(), 300),
                            normalize(g.get("target").getAsString()), g.has("granted") ? g.get("granted").getAsLong() : 0));
                }
            }
            if (o.has("lanGrants")) {
                for (JsonElement e : o.getAsJsonArray("lanGrants")) {
                    JsonObject g = e.getAsJsonObject();
                    try {
                        LAN.add(new LanGrant(g.get("host").getAsString(), g.get("port").getAsInt()));
                    } catch (RuntimeException ignored) {
                        // skip malformed entry
                    }
                }
            }
        });
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        JsonArray grants = new JsonArray();
        for (Grant g : REMEMBERED) {
            JsonObject j = new JsonObject();
            j.addProperty("context", g.context());
            j.addProperty("target", g.target());
            j.addProperty("granted", g.grantedAtMillis());
            grants.add(j);
        }
        o.add("grants", grants);
        JsonArray lan = new JsonArray();
        for (LanGrant g : LAN) {
            JsonObject j = new JsonObject();
            j.addProperty("host", g.host());
            j.addProperty("port", g.port());
            lan.add(j);
        }
        o.add("lanGrants", lan);
        LocalStore.write(FILE, o);
    }

    private static String normalize(String target) {
        return TextSanitizer.sanitize(target, 253).toLowerCase(Locale.ROOT);
    }

    private static String key(String context, String target) {
        return context + "|" + normalize(target);
    }

    /** Whether the player may contact {@code target} (a host or service id) in {@code context}. */
    public static boolean isGranted(String context, String target) {
        ensureLoaded();
        if (OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.get() || OteTvClientConfig.EXTERNAL_MEDIA.get() == OteTvClientConfig.ExternalMedia.DENY) {
            return false;
        }
        String t = normalize(target);
        if (SESSION_ONLY.contains(key(context, t))) {
            return true;
        }
        for (Grant g : REMEMBERED) {
            if (g.context().equals(context) && g.target().equals(t)) {
                return true;
            }
        }
        return OteTvClientConfig.EXTERNAL_MEDIA.get() == OteTvClientConfig.ExternalMedia.ALLOW && rememberAllowed(context, t);
    }

    private static boolean rememberAllowed(String context, String target) {
        grant(context, target, true);
        return true;
    }

    public static void grant(String context, String target, boolean remember) {
        ensureLoaded();
        String t = normalize(target);
        if (remember) {
            REMEMBERED.removeIf(g -> g.context().equals(context) && g.target().equals(t));
            REMEMBERED.add(new Grant(context, t, System.currentTimeMillis()));
            save();
        } else {
            SESSION_ONLY.add(key(context, t));
        }
        fire();
    }

    public static void revoke(String context, String target) {
        ensureLoaded();
        String t = normalize(target);
        REMEMBERED.removeIf(g -> g.context().equals(context) && g.target().equals(t));
        SESSION_ONLY.remove(key(context, t));
        save();
        fire();
    }

    public static void revokeAll() {
        ensureLoaded();
        REMEMBERED.clear();
        SESSION_ONLY.clear();
        save();
        fire();
    }

    public static List<Grant> grants() {
        ensureLoaded();
        return new ArrayList<>(REMEMBERED);
    }

    public static List<LanGrant> lanGrants() {
        ensureLoaded();
        return new ArrayList<>(LAN);
    }

    public static void addLanGrant(LanGrant grant) {
        ensureLoaded();
        if (!LAN.contains(grant)) {
            LAN.add(grant);
            save();
            fire();
        }
    }

    public static void removeLanGrant(LanGrant grant) {
        ensureLoaded();
        if (LAN.remove(grant)) {
            save();
            fire();
        }
    }

    /** Changing trust policy immediately stops now-disallowed sessions (section 16.4). */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    private static void fire() {
        for (Runnable r : LISTENERS) {
            r.run();
        }
    }
}
