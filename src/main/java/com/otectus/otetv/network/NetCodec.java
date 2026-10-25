package com.otectus.otetv.network;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceIssue;
import com.otectus.otetv.core.source.SourceType;
import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded codecs for sources and text. Every count and length is checked before allocation; sources are rebuilt
 * through {@link MediaSource#fromFields}, the single validating path. Local-file sources can never be encoded.
 */
public final class NetCodec {
    private static final int MAX_TYPE_ID = 32;

    private NetCodec() {
    }

    public static void writeSource(FriendlyByteBuf buf, @Nullable MediaSource source) {
        if (source == null) {
            buf.writeBoolean(false);
            return;
        }
        if (!source.isShareable()) {
            throw new IllegalArgumentException("private sources are never sent over the network");
        }
        buf.writeBoolean(true);
        buf.writeUtf(source.type().id(), MAX_TYPE_ID);
        Map<String, String> fields = source.toFields();
        buf.writeVarInt(fields.size());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            buf.writeUtf(e.getKey(), MediaSource.MAX_FIELD_KEY);
            buf.writeUtf(e.getValue(), Limits.MAX_URL_CHARS);
        }
    }

    /** Reads an optional source; returns null for "none". Throws on any structural violation. */
    @Nullable
    public static MediaSource readSource(FriendlyByteBuf buf, MediaSource.Policy policy) throws SourceException {
        if (!buf.readBoolean()) {
            return null;
        }
        String typeId = buf.readUtf(MAX_TYPE_ID);
        SourceType type = SourceType.byId(typeId).orElseThrow(() -> new SourceException(SourceIssue.UNKNOWN_TYPE, typeId));
        if (!type.isShareable()) {
            throw new SourceException(SourceIssue.NOT_SHAREABLE);
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MediaSource.MAX_FIELDS) {
            throw new SourceException(SourceIssue.TOO_LONG, "field count");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(MediaSource.MAX_FIELD_KEY);
            String value = buf.readUtf(Limits.MAX_URL_CHARS);
            fields.put(key, value);
        }
        return MediaSource.fromFields(type, fields, policy);
    }

    /** Reads a source where a malformed one must abort decoding of the whole packet. */
    @Nullable
    public static MediaSource readSourceOrFail(FriendlyByteBuf buf, MediaSource.Policy policy) {
        try {
            return readSource(buf, policy);
        } catch (SourceException e) {
            throw new DecoderException("invalid source: " + e.issue());
        }
    }

    public static CompoundTag sourceToTag(MediaSource source) {
        if (!source.isShareable()) {
            throw new IllegalArgumentException("private sources are never saved in world data");
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", source.type().id());
        CompoundTag fields = new CompoundTag();
        source.toFields().forEach(fields::putString);
        tag.put("fields", fields);
        return tag;
    }

    public static MediaSource sourceFromTag(CompoundTag tag, MediaSource.Policy policy) throws SourceException {
        SourceType type = SourceType.byId(tag.getString("type")).orElseThrow(() -> new SourceException(SourceIssue.UNKNOWN_TYPE));
        if (!type.isShareable()) {
            throw new SourceException(SourceIssue.NOT_SHAREABLE);
        }
        CompoundTag f = tag.getCompound("fields");
        if (f.size() > MediaSource.MAX_FIELDS) {
            throw new SourceException(SourceIssue.TOO_LONG, "field count");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : f.getAllKeys()) {
            if (f.getTagType(key) == Tag.TAG_STRING) {
                fields.put(key, f.getString(key));
            }
        }
        return MediaSource.fromFields(type, fields, policy);
    }

    public static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, E[] values) {
        int ordinal = buf.readVarInt();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("enum ordinal out of range: " + ordinal);
        }
        return values[ordinal];
    }

    public static long readBoundedLong(FriendlyByteBuf buf, long min, long max) {
        long v = buf.readLong();
        if (v < min || v > max) {
            throw new DecoderException("value out of range: " + v);
        }
        return v;
    }

    public static int readBoundedVarInt(FriendlyByteBuf buf, int min, int max) {
        int v = buf.readVarInt();
        if (v < min || v > max) {
            throw new DecoderException("value out of range: " + v);
        }
        return v;
    }

    public static double readFiniteDouble(FriendlyByteBuf buf, double min, double max) {
        double v = buf.readDouble();
        if (!Double.isFinite(v) || v < min || v > max) {
            throw new DecoderException("non-finite or out-of-range number");
        }
        return v;
    }
}
