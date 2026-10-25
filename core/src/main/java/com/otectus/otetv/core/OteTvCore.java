package com.otectus.otetv.core;

/** Identity and version constants shared by the mod, the workers, and the tests. */
public final class OteTvCore {
    public static final String MOD_ID = "otetv";

    /**
     * Reverse-DNS application identifier. YouTube's required-minimum-functionality rules ask WebView and desktop
     * integrations to identify themselves with a Referer of {@code https://<app id>}; the browser worker serves its
     * wrapper page from this origin. See docs/adr/0003-youtube-identity.md.
     */
    public static final String APP_ID = "com.otectus.otetv";

    /** Forge network channel protocol. Bump on any incompatible packet change. */
    public static final int NETWORK_PROTOCOL_VERSION = 1;

    /** Client-to-worker IPC protocol. The worker refuses a HELLO with a different value. */
    public static final int IPC_PROTOCOL_VERSION = 1;

    /** Persistent TV block-entity schema. */
    public static final int TV_SCHEMA_VERSION = 1;

    /** World-level saved data schema (TV registry, world namespace, admin channels). */
    public static final int WORLD_DATA_SCHEMA_VERSION = 1;

    /** Channel definition JSON schema. */
    public static final int CHANNEL_SCHEMA_VERSION = 1;

    private OteTvCore() {
    }
}
