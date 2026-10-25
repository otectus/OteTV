package com.otectus.otetv.core.channel;

import com.otectus.otetv.core.source.MediaSource;

import java.util.Objects;

/**
 * One approved catalog entry (section 18.3): a number, a display name (literal or translation key), an optional
 * owner-supplied description and a validated source. Channels are a small ordered catalog, not an IPTV directory.
 */
public record ChannelDefinition(String id, int number, String displayName, boolean translatable, String description,
                                MediaSource source, boolean enabled, Origin origin) {
    public enum Origin { DATAPACK, ADMIN }

    public ChannelDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        if (!source.isShareable()) {
            throw new IllegalArgumentException("channel sources must be shareable");
        }
    }

    /** Local-asset channels need each viewer to import a matching file (section 18.3). */
    public boolean requiresLocalAsset() {
        return source.type() == com.otectus.otetv.core.source.SourceType.SHARED_LOCAL_ASSET;
    }

    public ChannelDefinition withEnabled(boolean value) {
        return new ChannelDefinition(id, number, displayName, translatable, description, source, value, origin);
    }
}
