package com.otectus.otetv.core.source;

/** How media bytes reach the decoder. Determines which client playback adapter handles a source. */
public enum TransportClass {
    BUNDLED_ASSET,
    PROGRESSIVE_HTTP,
    HLS,
    BROWSER_PLAYER,
    LOCAL_FILE
}
