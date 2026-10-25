package com.otectus.otetv.network;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.sync.TimelineAnchor;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * An ordered, bounded change set (section 9.3 TvDelta). A client applies it only when {@code fromRevision} equals its
 * known revision in the same epoch; otherwise it requests a fresh snapshot, which handles reordering and loss.
 * Role or ACL changes are never sent as deltas: affected players receive individual snapshots instead.
 */
public record TvDelta(UUID tvId, long epoch, long fromRevision, long revision, int mask,
                      boolean powered, int sourceGeneration, @Nullable MediaSource source, TimelineAnchor timeline,
                      int channelNumber, String channelId, String channelLabel, String displayName, float speakerGain,
                      VisualPreset preset, RedstoneMode redstoneMode, int queueSize, int queueIndex,
                      String leaseHolder, int leaseGeneration, long leaseRemainingMs, int viewerCount) {
    public static final int POWER = 1;
    public static final int SOURCE = 1 << 1;
    public static final int TIMELINE = 1 << 2;
    public static final int CHANNEL = 1 << 3;
    public static final int NAME = 1 << 4;
    public static final int GAIN = 1 << 5;
    public static final int PRESET = 1 << 6;
    public static final int REDSTONE = 1 << 7;
    public static final int QUEUE = 1 << 8;
    public static final int LEASE = 1 << 9;
    public static final int VIEWERS = 1 << 10;
    public static final int ALL = (1 << 11) - 1;

    public boolean has(int bit) {
        return (mask & bit) != 0;
    }

    public static TvDelta of(TvSnapshot s, long fromRevision, int mask) {
        return new TvDelta(s.tvId(), s.epoch(), fromRevision, s.revision(), mask, s.powered(), s.sourceGeneration(), s.source(),
                s.timeline(), s.channelNumber(), s.channelId(), s.channelLabel(), s.displayName(), s.speakerGain(), s.preset(),
                s.redstoneMode(), s.queueSize(), s.queueIndex(), s.leaseHolder(), s.leaseGeneration(), s.leaseRemainingMs(), s.viewerCount());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(tvId);
        buf.writeLong(epoch);
        buf.writeVarLong(fromRevision);
        buf.writeVarLong(revision);
        buf.writeVarInt(mask);
        if (has(POWER)) {
            buf.writeBoolean(powered);
        }
        if (has(SOURCE)) {
            buf.writeVarInt(sourceGeneration);
            NetCodec.writeSource(buf, source);
        }
        if (has(TIMELINE)) {
            TvSnapshot.writeTimeline(buf, timeline);
        }
        if (has(CHANNEL)) {
            buf.writeVarInt(channelNumber);
            buf.writeUtf(channelId, 128);
            buf.writeUtf(channelLabel, Limits.MAX_DISPLAY_NAME * 4);
        }
        if (has(NAME)) {
            buf.writeUtf(displayName, Limits.MAX_DISPLAY_NAME * 4);
        }
        if (has(GAIN)) {
            buf.writeFloat(speakerGain);
        }
        if (has(PRESET)) {
            buf.writeVarInt(preset.ordinal());
        }
        if (has(REDSTONE)) {
            buf.writeVarInt(redstoneMode.ordinal());
        }
        if (has(QUEUE)) {
            buf.writeVarInt(queueSize);
            buf.writeVarInt(queueIndex + 1);
        }
        if (has(LEASE)) {
            buf.writeUtf(leaseHolder, Limits.MAX_PLAYER_NAME * 4);
            buf.writeVarInt(leaseGeneration);
            buf.writeVarLong(leaseRemainingMs);
        }
        if (has(VIEWERS)) {
            buf.writeVarInt(viewerCount);
        }
    }

    public static TvDelta decode(FriendlyByteBuf buf) {
        UUID tv = buf.readUUID();
        long epoch = buf.readLong();
        long from = buf.readVarLong();
        long revision = buf.readVarLong();
        int mask = buf.readVarInt();
        if ((mask & ~ALL) != 0) {
            throw new DecoderException("unknown delta bits");
        }
        boolean powered = false;
        int generation = 0;
        MediaSource source = null;
        TimelineAnchor timeline = TimelineAnchor.STOPPED;
        int channelNumber = 0;
        String channelId = "";
        String channelLabel = "";
        String name = "";
        float gain = 0;
        VisualPreset preset = VisualPreset.CLEAR;
        RedstoneMode redstone = RedstoneMode.IGNORE;
        int queueSize = 0;
        int queueIndex = -1;
        String leaseHolder = "";
        int leaseGeneration = 0;
        long leaseRemaining = 0;
        int viewers = 0;
        if ((mask & POWER) != 0) {
            powered = buf.readBoolean();
        }
        if ((mask & SOURCE) != 0) {
            generation = buf.readVarInt();
            source = NetCodec.readSourceOrFail(buf, MediaSource.Policy.STRUCTURAL);
        }
        if ((mask & TIMELINE) != 0) {
            timeline = TvSnapshot.readTimeline(buf);
        }
        if ((mask & CHANNEL) != 0) {
            channelNumber = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_CHANNEL_NUMBER);
            channelId = buf.readUtf(128);
            channelLabel = buf.readUtf(Limits.MAX_DISPLAY_NAME * 4);
        }
        if ((mask & NAME) != 0) {
            name = buf.readUtf(Limits.MAX_DISPLAY_NAME * 4);
        }
        if ((mask & GAIN) != 0) {
            gain = buf.readFloat();
            if (!Float.isFinite(gain) || gain < 0 || gain > 1) {
                throw new DecoderException("bad gain");
            }
        }
        if ((mask & PRESET) != 0) {
            preset = NetCodec.readEnum(buf, VisualPreset.values());
        }
        if ((mask & REDSTONE) != 0) {
            redstone = NetCodec.readEnum(buf, RedstoneMode.values());
        }
        if ((mask & QUEUE) != 0) {
            queueSize = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE);
            queueIndex = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE) - 1;
        }
        if ((mask & LEASE) != 0) {
            leaseHolder = buf.readUtf(Limits.MAX_PLAYER_NAME * 4);
            leaseGeneration = buf.readVarInt();
            leaseRemaining = buf.readVarLong();
        }
        if ((mask & VIEWERS) != 0) {
            viewers = NetCodec.readBoundedVarInt(buf, 0, 10_000);
        }
        return new TvDelta(tv, epoch, from, revision, mask, powered, generation, source, timeline, channelNumber, channelId,
                channelLabel, name, gain, preset, redstone, queueSize, queueIndex, leaseHolder, leaseGeneration, leaseRemaining, viewers);
    }
}
