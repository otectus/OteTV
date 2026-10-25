package com.otectus.otetv.block.entity;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.core.text.TextSanitizer;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What every player who can see a TV learns from ordinary block-entity sync: its title, source label and host, and
 * coarse play state, so a shared TV can show "Join viewing" without fetching anything (section 7.1). Full source
 * details and the timeline travel only in snapshots to players who ask from within range.
 */
public record TvPublicState(@Nullable UUID tvId, String displayName, boolean powered, String ownerName, int channelNumber,
                            String sourceType, String sourceLabel, String sourceHost, PlayState playState, VisualPreset preset,
                            long epoch, long revision, int generation, boolean locked) {
    public static final TvPublicState EMPTY = new TvPublicState(null, "", false, "", 0, "", "", "", PlayState.STOPPED,
            VisualPreset.CLEAR, 0, 0, 0, false);

    public boolean hasSource() {
        return !sourceType.isEmpty();
    }

    public CompoundTag write() {
        CompoundTag t = new CompoundTag();
        if (tvId != null) {
            t.putUUID("tv", tvId);
        }
        t.putString("name", displayName);
        t.putBoolean("power", powered);
        t.putString("owner", ownerName);
        t.putInt("channel", channelNumber);
        t.putString("type", sourceType);
        t.putString("label", sourceLabel);
        t.putString("host", sourceHost);
        t.putByte("play", (byte) playState.ordinal());
        t.putByte("preset", (byte) preset.ordinal());
        t.putLong("epoch", epoch);
        t.putLong("revision", revision);
        t.putInt("generation", generation);
        t.putBoolean("locked", locked);
        return t;
    }

    /** Client-side decode; values are sanitized and bounded like any other server-provided text. */
    public static TvPublicState read(CompoundTag t) {
        return new TvPublicState(t.hasUUID("tv") ? t.getUUID("tv") : null,
                TextSanitizer.sanitize(t.getString("name"), Limits.MAX_DISPLAY_NAME),
                t.getBoolean("power"),
                TextSanitizer.sanitize(t.getString("owner"), Limits.MAX_PLAYER_NAME),
                Math.max(0, Math.min(Limits.MAX_CHANNEL_NUMBER, t.getInt("channel"))),
                TextSanitizer.sanitize(t.getString("type"), 32),
                TextSanitizer.sanitize(t.getString("label"), Limits.MAX_LABEL),
                TextSanitizer.sanitize(t.getString("host"), 253),
                PlayState.byOrdinal(t.getByte("play")),
                VisualPreset.byOrdinal(t.getByte("preset")),
                t.getLong("epoch"), t.getLong("revision"), t.getInt("generation"), t.getBoolean("locked"));
    }
}
