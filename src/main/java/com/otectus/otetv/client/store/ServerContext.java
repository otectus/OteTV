package com.otectus.otetv.client.store;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

import java.util.Locale;

/**
 * Identifies the current server context for scoping local trust decisions (section 7.1): "singleplayer:<world>" or
 * "server:<address>". A grant on one server never applies to another.
 */
public final class ServerContext {
    private ServerContext() {
    }

    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer integrated = mc.getSingleplayerServer();
        if (integrated != null && !integrated.isPublished()) {
            return "singleplayer:" + integrated.getWorldData().getLevelName();
        }
        if (integrated != null) {
            return "lan-host:" + integrated.getWorldData().getLevelName();
        }
        ServerData data = mc.getCurrentServer();
        if (data != null) {
            String ip = data.ip.toLowerCase(Locale.ROOT).trim();
            return (data.isLan() ? "lan:" : "server:") + ip;
        }
        return "unknown";
    }

    /** A readable label for the consent screen. */
    public static String describe(String context) {
        int colon = context.indexOf(':');
        return colon < 0 ? context : context.substring(colon + 1);
    }
}
