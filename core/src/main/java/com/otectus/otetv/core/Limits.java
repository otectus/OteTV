package com.otectus.otetv.core;

/**
 * Bounds applied before allocation everywhere untrusted data enters: packets, worker IPC, datapacks and saves.
 * Values follow section 9.3 of the implementation brief unless noted.
 */
public final class Limits {
    /** Largest decoded application payload accepted from a client. */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    public static final int MAX_URL_CHARS = 2048;
    public static final int MAX_DISPLAY_NAME = 64;
    public static final int MAX_LABEL = 96;
    public static final int MAX_DESCRIPTION = 256;
    public static final int MAX_CHANNELS = 128;
    public static final int MAX_QUEUE = 32;
    public static final int CATALOG_PAGE_SIZE = 32;
    public static final int MAX_TRUSTED = 64;
    public static final int MAX_PLAYER_NAME = 16;
    public static final int MAX_ASSET_ID = 128;
    public static final int MAX_CHANNEL_NUMBER = 9999;
    public static final int MAX_DETAIL = 512;

    public static final double ACTIONS_PER_SECOND = 10.0;
    public static final int ACTION_BURST = 20;
    public static final long VIEWER_REPORT_MIN_INTERVAL_MS = 1000;
    public static final long CLOCK_PROBE_MIN_INTERVAL_MS = 200;
    public static final long TIMING_BROADCAST_INTERVAL_MS = 2000;
    public static final long LEASE_DURATION_MS = 30_000;
    /** Approximate lead for scheduled group starts (section 10.3); tuned from measured latency. */
    public static final long SCHEDULED_START_LEAD_MS = 500;
    /** Positions beyond 48 hours are rejected as implausible. */
    public static final long MAX_POSITION_MS = 48L * 3600 * 1000;
    public static final int MAX_START_SECONDS = 48 * 3600;
    public static final double MIN_SPEAKER_GAIN = 0.0;
    public static final double MAX_SPEAKER_GAIN = 1.0;

    /** Output frame ceiling. Higher resolutions are refused, never silently allocated (section 14.3). */
    public static final int MAX_FRAME_WIDTH = 1920;
    public static final int MAX_FRAME_HEIGHT = 1080;

    private Limits() {
    }
}
