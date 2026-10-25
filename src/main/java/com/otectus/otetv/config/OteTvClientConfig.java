package com.otectus.otetv.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client configuration (section 18.2). Local trust grants, LAN grants, local-file mappings and history are stored
 * separately in the client's private OteTV directory, never in this synced-style config.
 */
public final class OteTvClientConfig {
    public enum ExternalMedia { ASK, ALLOW, DENY }

    public enum QualityPreset {
        LOW(640, 360, 24), BALANCED(960, 540, 30), HIGH(1280, 720, 30), FULL_HD(1920, 1080, 30);

        public final int width;
        public final int height;
        public final int fps;

        QualityPreset(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    public enum RuntimeAutoUpdate { NOTIFY, NEVER }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<ExternalMedia> EXTERNAL_MEDIA;
    public static final ForgeConfigSpec.BooleanValue DISABLE_EXTERNAL_MEDIA;
    public static final ForgeConfigSpec.BooleanValue AUTO_JOIN_SHARED_TVS;
    public static final ForgeConfigSpec.EnumValue<QualityPreset> QUALITY_PRESET;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_PLAYERS;
    public static final ForgeConfigSpec.DoubleValue LOCAL_VOLUME;
    public static final ForgeConfigSpec.BooleanValue GLOBAL_MUTE;
    public static final ForgeConfigSpec.IntValue MAX_AUDIO_RANGE;
    public static final ForgeConfigSpec.BooleanValue REDUCE_MOTION_AND_FLICKER;
    public static final ForgeConfigSpec.BooleanValue DISABLE_PICTURE_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue STARTUP_SOUNDS;
    public static final ForgeConfigSpec.BooleanValue CRT_HUM;
    public static final ForgeConfigSpec.BooleanValue PAUSE_WHEN_UNFOCUSED;
    public static final ForgeConfigSpec.BooleanValue PERSIST_RECENT_HISTORY;
    public static final ForgeConfigSpec.EnumValue<RuntimeAutoUpdate> RUNTIME_AUTO_UPDATE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue HIGH_CONTRAST;
    public static final ForgeConfigSpec.BooleanValue CAPTIONS;
    public static final ForgeConfigSpec.DoubleValue CAPTION_SCALE;
    public static final ForgeConfigSpec.BooleanValue SAFE_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> RUNTIME_MANIFEST;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("privacy");
        EXTERNAL_MEDIA = b.comment("ASK: confirm each new host/service per server before your computer contacts it. ALLOW: remember",
                        "approval automatically. DENY: never contact external media hosts.")
                .defineEnum("externalMedia", ExternalMedia.ASK);
        DISABLE_EXTERNAL_MEDIA = b.comment("Global switch: no external media requests at all (bundled demo and local files still work).")
                .define("disableExternalMedia", false);
        AUTO_JOIN_SHARED_TVS = b.comment("Joining a server or seeing a TV never fetches media on its own unless this is enabled.")
                .define("autoJoinSharedTvs", false);
        PERSIST_RECENT_HISTORY = b.comment("Keep a private recent-history list on disk.")
                .define("persistRecentHistory", false);
        b.pop().push("playback");
        QUALITY_PRESET = b.comment("Output size and frame rate for direct media: LOW 640x360@24, BALANCED 960x540@30, HIGH 1280x720@30,",
                        "FULL_HD 1920x1080@30 (only after profiling).")
                .defineEnum("qualityPreset", QualityPreset.BALANCED);
        MAX_ACTIVE_PLAYERS = b.comment("Active playback sessions on this client, including at most one YouTube player.")
                .defineInRange("maxActivePlayers", 2, 1, 2);
        LOCAL_VOLUME = b.comment("Personal volume, multiplied by master, jukebox/record, TV speaker and distance gains.")
                .defineInRange("localVolume", 0.5, 0.0, 1.0);
        GLOBAL_MUTE = b.comment("Mute every TV for you only.").define("globalMute", false);
        MAX_AUDIO_RANGE = b.comment("Audible range in blocks.").defineInRange("maxAudioRangeBlocks", 16, 4, 64);
        PAUSE_WHEN_UNFOCUSED = b.comment("Pause local playback while the game window is unfocused (always enforced for YouTube).")
                .define("pauseWhenUnfocused", true);
        CAPTIONS = b.comment("Show captions when available (per viewer).").define("captions", false);
        CAPTION_SCALE = b.comment("Caption text size multiplier.").defineInRange("captionScale", 1.0, 0.5, 3.0);
        b.pop().push("accessibility");
        REDUCE_MOTION_AND_FLICKER = b.comment("Calm presentation: no flicker, grain or animated static.")
                .define("reduceMotionAndFlicker", true);
        DISABLE_PICTURE_EFFECTS = b.comment("Disable period picture effects (monochrome, scanlines, vignette) for all TVs.")
                .define("disablePictureEffects", false);
        STARTUP_SOUNDS = b.comment("Play power-on and channel sounds.").define("startupSounds", true);
        CRT_HUM = b.comment("Play a quiet hum near powered CRT-era sets.").define("crtHum", false);
        HIGH_CONTRAST = b.comment("High-contrast colors in OteTV screens.").define("highContrast", false);
        b.pop().push("runtime");
        RUNTIME_AUTO_UPDATE = b.comment("NOTIFY: tell you about a verified runtime update; it is only activated after you confirm.")
                .defineEnum("runtimeAutoUpdate", RuntimeAutoUpdate.NOTIFY);
        RUNTIME_MANIFEST = b.comment("Optional local path or https URL of an additional runtime manifest you trust. Never taken from a server.")
                .define("runtimeManifest", "");
        SAFE_MODE = b.comment("Diagnostic safe mode: disable picture effects and hardware acceleration in workers. Security is unchanged.")
                .define("safeMode", false);
        DEBUG_LOGGING = b.comment("Verbose logs. Redaction of URLs, paths and tokens still applies.").define("debugLogging", false);
        b.pop();
        SPEC = b.build();
    }

    private OteTvClientConfig() {
    }
}
