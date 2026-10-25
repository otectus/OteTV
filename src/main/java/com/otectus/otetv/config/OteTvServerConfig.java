package com.otectus.otetv.config;

import com.otectus.otetv.session.ServerSourcePolicy;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Server configuration (section 18.1). Per-world, synced to clients by Forge. Values are clamped by the spec ranges
 * and re-read on every use, so edits apply on reload without restarting. A permissive server setting never overrides
 * a restrictive client setting: effective permission is the intersection with each client's local consent.
 */
public final class OteTvServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PUBLIC_CONTROLS;
    public static final ForgeConfigSpec.IntValue REMOTE_RANGE;
    public static final ForgeConfigSpec.IntValue VIEWER_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_TVS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_SESSIONS_PER_DIMENSION;
    public static final ForgeConfigSpec.IntValue MAX_CHANNELS;
    public static final ForgeConfigSpec.IntValue MAX_QUEUE_ENTRIES;
    public static final ForgeConfigSpec.EnumValue<ServerSourcePolicy.Mode> SOURCE_POLICY;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> APPROVED_HOSTS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_YOUTUBE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ARBITRARY_WEB_BROWSING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PRIVATE_NETWORK_SOURCES;
    public static final ForgeConfigSpec.BooleanValue AUTO_PAUSE_EMPTY_VOD;
    public static final ForgeConfigSpec.IntValue AUTO_PAUSE_GRACE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_REDSTONE_CONTROL;
    public static final ForgeConfigSpec.ConfigValue<String> SUBSCRIPTION_IN_WORLD_ADAPTERS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("OteTV server settings. These narrow what clients may do; they can never enable a client's networking,",
                "account login, autoplay or local-file access.").push("general");
        ALLOW_PUBLIC_CONTROLS = b.comment("Let TV owners enable playback controls for every viewer (each TV still has to opt in).")
                .define("allowPublicControls", false);
        REMOTE_RANGE = b.comment("Maximum distance in blocks for remote and TV actions, validated on the server (same dimension only).")
                .defineInRange("remoteRangeBlocks", 32, 4, 128);
        VIEWER_RANGE = b.comment("Distance within which players may join a TV's viewing session and count as viewers for auto-pause.")
                .defineInRange("viewerRangeBlocks", 64, 8, 256);
        MAX_TVS_PER_PLAYER = b.comment("Placement limit per player. Operators are exempt.")
                .defineInRange("maxTvsPerPlayer", 16, 0, 1024);
        MAX_ACTIVE_SESSIONS_PER_DIMENSION = b.comment("Ceiling on simultaneously playing TV sessions per dimension.")
                .defineInRange("maxActiveSessionsPerDimension", 32, 1, 1024);
        MAX_CHANNELS = b.comment("Channel catalog cap (datapack plus admin channels).")
                .defineInRange("maxChannels", 128, 1, 128);
        MAX_QUEUE_ENTRIES = b.comment("Per-TV queue cap.")
                .defineInRange("maxQueueEntries", 32, 1, 32);
        b.pop().push("sources");
        SOURCE_POLICY = b.comment("BUNDLED_ONLY: only bundled media. APPROVED_HOSTS: bundled, YouTube (if allowed), shared local assets,",
                        "and https media from approvedHosts. ANY_PUBLIC_HTTPS: any public https host (clients still ask their player first).")
                .defineEnum("sourcePolicy", ServerSourcePolicy.Mode.APPROVED_HOSTS);
        APPROVED_HOSTS = b.comment("Hosts allowed for direct https/HLS media under APPROVED_HOSTS. Prefix with '.' or '*.' for subdomains.")
                .defineListAllowEmpty("approvedHosts", List.of(), o -> o instanceof String s && s.length() <= 253 && !s.isBlank());
        ALLOW_YOUTUBE = b.comment("Let eligible clients use the official YouTube embedded player adapter.")
                .define("allowYouTube", true);
        ALLOW_ARBITRARY_WEB_BROWSING = b.comment("Not implemented in this release; the browser adapter is restricted to the official YouTube player.")
                .define("allowArbitraryWebBrowsing", false);
        ALLOW_PRIVATE_NETWORK_SOURCES = b.comment("Allow channel/source URLs on private networks or plain http. Each client still needs its own",
                        "local LAN grant before fetching; the server cannot create one.")
                .define("allowPrivateNetworkSources", false);
        SUBSCRIPTION_IN_WORLD_ADAPTERS = b.comment("Subscription services have no qualified in-world adapter in this release; they open in the",
                        "player's own browser only. This value is informational and must stay 'disabled'.")
                .define("subscriptionInWorldAdapters", "disabled", o -> "disabled".equals(o));
        b.pop().push("behaviour");
        AUTO_PAUSE_EMPTY_VOD = b.comment("Pause a playing on-demand session when no viewer is in range.")
                .define("autoPauseEmptyVOD", true);
        AUTO_PAUSE_GRACE_SECONDS = b.comment("Grace period before auto-pausing.")
                .defineInRange("autoPauseGraceSeconds", 15, 1, 600);
        ALLOW_REDSTONE_CONTROL = b.comment("Let redstone change power/channel according to each TV's redstone mode.")
                .define("allowRedstoneControl", true);
        b.pop();
        SPEC = b.build();
    }

    private OteTvServerConfig() {
    }
}
