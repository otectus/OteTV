package com.otectus.otetv.core.source;

import java.util.Locale;
import java.util.Optional;

/**
 * The explicitly enumerated source kinds (section 20.1). Channel JSON, packets and saves name these ids; no class
 * name, script or parser is ever accepted in their place.
 */
public enum SourceType {
    BUNDLED("bundled", TransportClass.BUNDLED_ASSET, true),
    HTTPS_VIDEO("https_video", TransportClass.PROGRESSIVE_HTTP, true),
    HLS_CLEAR("hls_clear", TransportClass.HLS, true),
    YOUTUBE("youtube", TransportClass.BROWSER_PLAYER, true),
    SHARED_LOCAL_ASSET("shared_local_asset", TransportClass.LOCAL_FILE, true),
    /** A file picked by the local player for private viewing. Never serialized into packets or saves. */
    LOCAL_FILE("local_file", TransportClass.LOCAL_FILE, false);

    private final String id;
    private final TransportClass transport;
    private final boolean shareable;

    SourceType(String id, TransportClass transport, boolean shareable) {
        this.id = id;
        this.transport = transport;
        this.shareable = shareable;
    }

    public String id() {
        return id;
    }

    public TransportClass transport() {
        return transport;
    }

    /** Whether the type may appear in shared TV state, channel catalogs or packets. */
    public boolean isShareable() {
        return shareable;
    }

    public boolean requiresNetwork() {
        return this == HTTPS_VIDEO || this == HLS_CLEAR || this == YOUTUBE;
    }

    public boolean usesDirectMedia() {
        return transport != TransportClass.BROWSER_PLAYER;
    }

    public String translationKey() {
        return "otetv.source_type." + id;
    }

    public static Optional<SourceType> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.toLowerCase(Locale.ROOT);
        for (SourceType type : values()) {
            if (type.id.equals(key)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
