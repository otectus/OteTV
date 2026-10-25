package com.otectus.otetv.block.entity;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.text.TextSanitizer;
import com.otectus.otetv.network.NetCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-persistent TV state (section 9.1). Never contains cookies, tokens, local paths or private account data.
 * Loading is per-field defensive: a corrupt field falls back to a safe default without touching ownership; an
 * unknown future schema is preserved verbatim and the TV is locked rather than silently reset (section 9.2).
 */
public final class TvState {
    public record Trust(String name, TvRole role) {
    }

    UUID tvId;
    @Nullable
    UUID owner;
    String ownerName = "";
    final Map<UUID, Trust> trusted = new LinkedHashMap<>();
    boolean publicControls;
    String displayName = "";
    boolean powered;
    String channelId = "";
    int channelNumber;
    @Nullable
    MediaSource source;
    final List<MediaSource> queue = new ArrayList<>();
    int queueIndex = -1;
    long cursorMs;
    boolean intendedPlaying;
    float speakerGain = 0.8f;
    VisualPreset preset = VisualPreset.CLEAR;
    RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    boolean lastRedstoneHigh;
    long lastRedstoneChannelChange;
    long durationHintMs = -1;
    /** Raw data from a newer schema; when present the TV is read-only and saves write this back unchanged. */
    @Nullable
    CompoundTag incompatible;

    public boolean isLocked() {
        return incompatible != null;
    }

    public void save(CompoundTag tag) {
        if (incompatible != null) {
            for (String key : incompatible.getAllKeys()) {
                Tag value = incompatible.get(key);
                if (value != null) {
                    tag.put(key, value.copy());
                }
            }
            return;
        }
        tag.putInt("schema", OteTvCore.TV_SCHEMA_VERSION);
        if (tvId != null) {
            tag.putUUID("tv", tvId);
        }
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putString("ownerName", ownerName);
        ListTag trust = new ListTag();
        for (Map.Entry<UUID, Trust> e : trusted.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", e.getKey());
            t.putString("name", e.getValue().name());
            t.putString("role", e.getValue().role().id());
            trust.add(t);
        }
        tag.put("trusted", trust);
        tag.putBoolean("publicControls", publicControls);
        tag.putString("name", displayName);
        tag.putBoolean("power", powered);
        tag.putString("channel", channelId);
        tag.putInt("channelNumber", channelNumber);
        if (source != null) {
            tag.put("source", NetCodec.sourceToTag(source));
        }
        ListTag q = new ListTag();
        for (MediaSource s : queue) {
            q.add(NetCodec.sourceToTag(s));
        }
        tag.put("queue", q);
        tag.putInt("queueIndex", queueIndex);
        tag.putLong("cursorMs", cursorMs);
        tag.putBoolean("intendedPlaying", intendedPlaying);
        tag.putFloat("gain", speakerGain);
        tag.putString("preset", preset.id());
        tag.putString("redstone", redstoneMode.id());
        tag.putBoolean("redstoneHigh", lastRedstoneHigh);
        tag.putLong("durationHint", durationHintMs);
    }

    public static TvState load(CompoundTag tag, VisualPreset defaultPreset) {
        TvState s = new TvState();
        s.preset = defaultPreset;
        if (tag.isEmpty()) {
            return s;
        }
        int schema = tag.getInt("schema");
        if (tag.hasUUID("tv")) {
            s.tvId = tag.getUUID("tv");
        }
        if (tag.hasUUID("owner")) {
            s.owner = tag.getUUID("owner");
        }
        if (schema > OteTvCore.TV_SCHEMA_VERSION) {
            OteTV.LOGGER.warn("Television {} was saved by a newer OteTV (schema {}); it is locked read-only until updated", s.tvId, schema);
            s.incompatible = tag.copy();
            s.ownerName = TextSanitizer.sanitize(tag.getString("ownerName"), Limits.MAX_PLAYER_NAME);
            return s;
        }
        // Schema 1 is the first released schema; migrations from later versions are added here step by step.
        s.ownerName = TextSanitizer.sanitize(tag.getString("ownerName"), Limits.MAX_PLAYER_NAME);
        ListTag trust = tag.getList("trusted", Tag.TAG_COMPOUND);
        for (int i = 0; i < trust.size() && s.trusted.size() < Limits.MAX_TRUSTED; i++) {
            CompoundTag t = trust.getCompound(i);
            if (!t.hasUUID("id")) {
                continue;
            }
            TvRole role = TvRole.byId(t.getString("role")).filter(TvRole::isAssignable).orElse(null);
            if (role != null) {
                s.trusted.put(t.getUUID("id"), new Trust(TextSanitizer.sanitize(t.getString("name"), Limits.MAX_PLAYER_NAME), role));
            }
        }
        s.publicControls = tag.getBoolean("publicControls");
        s.displayName = TextSanitizer.sanitize(tag.getString("name"), Limits.MAX_DISPLAY_NAME);
        s.powered = tag.getBoolean("power");
        s.channelId = TextSanitizer.sanitize(tag.getString("channel"), 128);
        s.channelNumber = Math.max(0, Math.min(Limits.MAX_CHANNEL_NUMBER, tag.getInt("channelNumber")));
        if (tag.contains("source", Tag.TAG_COMPOUND)) {
            try {
                s.source = NetCodec.sourceFromTag(tag.getCompound("source"), MediaSource.Policy.STRUCTURAL);
            } catch (SourceException e) {
                OteTV.LOGGER.warn("Television {} had an invalid saved source ({}); falling back to standby", s.tvId, e.issue());
            }
        }
        ListTag q = tag.getList("queue", Tag.TAG_COMPOUND);
        for (int i = 0; i < q.size() && s.queue.size() < Limits.MAX_QUEUE; i++) {
            try {
                s.queue.add(NetCodec.sourceFromTag(q.getCompound(i), MediaSource.Policy.STRUCTURAL));
            } catch (SourceException e) {
                OteTV.LOGGER.warn("Dropped an invalid queue entry from television {}", s.tvId);
            }
        }
        s.queueIndex = Math.max(-1, Math.min(s.queue.size() - 1, tag.contains("queueIndex") ? tag.getInt("queueIndex") : -1));
        s.cursorMs = Math.max(0, Math.min(Limits.MAX_POSITION_MS, tag.getLong("cursorMs")));
        s.intendedPlaying = tag.getBoolean("intendedPlaying");
        float gain = tag.contains("gain") ? tag.getFloat("gain") : 0.8f;
        s.speakerGain = Float.isFinite(gain) ? Math.max(0f, Math.min(1f, gain)) : 0.8f;
        s.preset = VisualPreset.byId(tag.getString("preset")).orElse(defaultPreset);
        s.redstoneMode = RedstoneMode.byId(tag.getString("redstone")).orElse(RedstoneMode.IGNORE);
        s.lastRedstoneHigh = tag.getBoolean("redstoneHigh");
        long hint = tag.contains("durationHint") ? tag.getLong("durationHint") : -1;
        s.durationHintMs = hint < -1 || hint > Limits.MAX_POSITION_MS ? -1 : hint;
        return s;
    }

    // Accessors used by the session layer and the API.

    public UUID tvId() {
        return tvId;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public Map<UUID, Trust> trusted() {
        return trusted;
    }

    public boolean publicControls() {
        return publicControls;
    }

    public String displayName() {
        return displayName;
    }

    public boolean powered() {
        return powered;
    }

    public String channelId() {
        return channelId;
    }

    public int channelNumber() {
        return channelNumber;
    }

    @Nullable
    public MediaSource source() {
        return source;
    }

    public List<MediaSource> queue() {
        return queue;
    }

    public int queueIndex() {
        return queueIndex;
    }

    public long cursorMs() {
        return cursorMs;
    }

    public float speakerGain() {
        return speakerGain;
    }

    public VisualPreset preset() {
        return preset;
    }

    public RedstoneMode redstoneMode() {
        return redstoneMode;
    }

    public long durationHintMs() {
        return durationHintMs;
    }

    public boolean lastRedstoneHigh() {
        return lastRedstoneHigh;
    }

    // Mutators, called only by the session layer on the server thread after validation.

    public void setOwner(@Nullable UUID id, String name) {
        owner = id;
        ownerName = TextSanitizer.sanitize(name, Limits.MAX_PLAYER_NAME);
        if (id != null) {
            trusted.remove(id);
        }
    }

    public void setPowered(boolean value) {
        powered = value;
    }

    public void setSource(@Nullable MediaSource value) {
        if (value != null && !value.isShareable()) {
            throw new IllegalArgumentException("private sources cannot become shared TV state");
        }
        source = value;
    }

    public void setChannel(String id, int number) {
        channelId = id == null ? "" : id;
        channelNumber = Math.max(0, Math.min(Limits.MAX_CHANNEL_NUMBER, number));
    }

    public void setQueueIndex(int index) {
        queueIndex = Math.max(-1, Math.min(queue.size() - 1, index));
    }

    public void setCursor(long ms, boolean playing) {
        cursorMs = Math.max(0, Math.min(Limits.MAX_POSITION_MS, ms));
        intendedPlaying = playing;
    }

    public void setSpeakerGain(float gain) {
        speakerGain = Float.isFinite(gain) ? Math.max(0f, Math.min(1f, gain)) : speakerGain;
    }

    public void setDisplayName(String name) {
        displayName = TextSanitizer.sanitize(name, Limits.MAX_DISPLAY_NAME);
    }

    public void setPreset(VisualPreset value) {
        preset = value;
    }

    public void setRedstoneMode(RedstoneMode value) {
        redstoneMode = value;
    }

    public void setLastRedstoneHigh(boolean value) {
        lastRedstoneHigh = value;
    }

    public void setPublicControls(boolean value) {
        publicControls = value;
    }

    public void setDurationHint(long ms) {
        durationHintMs = ms < -1 || ms > Limits.MAX_POSITION_MS ? -1 : ms;
    }

    public void trust(UUID id, String name, TvRole role) {
        if (!role.isAssignable()) {
            throw new IllegalArgumentException("role not assignable");
        }
        trusted.put(id, new Trust(name, role));
    }

    public void untrust(UUID id) {
        trusted.remove(id);
    }

    /** Administrative reset: playback, channel, queue and settings return to defaults. Identity and ACL remain. */
    public void resetPlayback(VisualPreset defaultPreset) {
        powered = false;
        source = null;
        channelId = "";
        channelNumber = 0;
        queue.clear();
        queueIndex = -1;
        cursorMs = 0;
        intendedPlaying = false;
        speakerGain = 0.8f;
        preset = defaultPreset;
        redstoneMode = RedstoneMode.IGNORE;
        durationHintMs = -1;
    }
}
