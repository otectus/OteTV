package com.otectus.otetv.client.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.text.TextSanitizer;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Private recent history and channel favorites/aliases (sections 7.2 and 18.3). Kept in memory unless the player opts
 * in to persistence; never broadcast as TV metadata.
 */
public final class PrivateHistory {
    private static final String HISTORY_FILE = "history.json";
    private static final String FAVORITES_FILE = "favorites.json";
    private static final int MAX_HISTORY = 30;
    private static final Deque<String> HISTORY = new LinkedList<>();
    private static final Map<Integer, String> ALIASES = new HashMap<>();
    private static final List<Integer> FAVORITES = new ArrayList<>();
    private static boolean loaded;

    private PrivateHistory() {
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (OteTvClientConfig.PERSIST_RECENT_HISTORY.get()) {
            LocalStore.read(HISTORY_FILE).ifPresent(json -> {
                if (json.isJsonArray()) {
                    for (JsonElement e : json.getAsJsonArray()) {
                        HISTORY.addLast(TextSanitizer.sanitize(e.getAsString(), 2048));
                    }
                }
            });
        }
        LocalStore.read(FAVORITES_FILE).ifPresent(json -> {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject o = json.getAsJsonObject();
            if (o.has("favorites")) {
                o.getAsJsonArray("favorites").forEach(e -> FAVORITES.add(e.getAsInt()));
            }
            if (o.has("aliases")) {
                o.getAsJsonObject("aliases").entrySet().forEach(e ->
                        ALIASES.put(Integer.parseInt(e.getKey()), TextSanitizer.sanitize(e.getValue().getAsString(), 32)));
            }
        });
    }

    public static synchronized void recordLink(String link) {
        ensureLoaded();
        HISTORY.remove(link);
        HISTORY.addFirst(link);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.removeLast();
        }
        if (OteTvClientConfig.PERSIST_RECENT_HISTORY.get()) {
            JsonArray arr = new JsonArray();
            HISTORY.forEach(arr::add);
            LocalStore.write(HISTORY_FILE, arr);
        }
    }

    public static synchronized List<String> recentLinks() {
        ensureLoaded();
        return new ArrayList<>(HISTORY);
    }

    public static synchronized void clearHistory() {
        HISTORY.clear();
        LocalStore.delete(HISTORY_FILE);
    }

    public static synchronized boolean isFavorite(int channel) {
        ensureLoaded();
        return FAVORITES.contains(channel);
    }

    public static synchronized void toggleFavorite(int channel) {
        ensureLoaded();
        if (!FAVORITES.remove((Integer) channel)) {
            FAVORITES.add(channel);
        }
        saveFavorites();
    }

    public static synchronized String alias(int channel) {
        ensureLoaded();
        return ALIASES.getOrDefault(channel, "");
    }

    public static synchronized void setAlias(int channel, String alias) {
        ensureLoaded();
        String clean = TextSanitizer.sanitize(alias, 32);
        if (clean.isEmpty()) {
            ALIASES.remove(channel);
        } else {
            ALIASES.put(channel, clean);
        }
        saveFavorites();
    }

    private static void saveFavorites() {
        JsonObject o = new JsonObject();
        JsonArray fav = new JsonArray();
        FAVORITES.forEach(fav::add);
        o.add("favorites", fav);
        JsonObject aliases = new JsonObject();
        ALIASES.forEach((k, v) -> aliases.addProperty(Integer.toString(k), v));
        o.add("aliases", aliases);
        LocalStore.write(FAVORITES_FILE, o);
    }
}
