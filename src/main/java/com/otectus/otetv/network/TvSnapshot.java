package com.otectus.otetv.network;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.core.sync.TimelineAnchor;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The public state of one TV as seen by one player (section 9.3 TvSnapshot): identity, revision, epoch, source,
 * timeline anchor, lease and the receiving player's own role. It deliberately excludes the ACL list, other viewers'
 * identities and any private client data. Queue contents and the channel catalog are paginated separately.
 */
public record TvSnapshot(
        UUID tvId, long epoch, long revision, int sourceGeneration,
        String model, String displayName, String ownerName, TvRole yourRole, int permissionBits, boolean publicControls,
        boolean powered, int channelNumber, String channelId, String channelLabel,
        @Nullable MediaSource source, TimelineAnchor timeline, float speakerGain, VisualPreset preset, RedstoneMode redstoneMode,
        int queueSize, int queueIndex, String leaseHolder, int leaseGeneration, long leaseRemainingMs, boolean leaseIsYours,
        int viewerCount, long catalogRevision, boolean locked, boolean youtubeAllowed, BlockPos pos, String dimension) {

    public Set<TvPermission> permissions() {
        EnumSet<TvPermission> set = EnumSet.noneOf(TvPermission.class);
        for (TvPermission p : TvPermission.values()) {
            if ((permissionBits & (1 << p.ordinal())) != 0) {
                set.add(p);
            }
        }
        return set;
    }

    public boolean hasLeaseHolder() {
        return !leaseHolder.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(tvId);
        buf.writeLong(epoch);
        buf.writeVarLong(revision);
        buf.writeVarInt(sourceGeneration);
        buf.writeUtf(model, 64);
        buf.writeUtf(displayName, Limits.MAX_DISPLAY_NAME * 4);
        buf.writeUtf(ownerName, Limits.MAX_PLAYER_NAME * 4);
        buf.writeVarInt(yourRole.ordinal());
        buf.writeVarInt(permissionBits);
        buf.writeBoolean(publicControls);
        buf.writeBoolean(powered);
        buf.writeVarInt(channelNumber);
        buf.writeUtf(channelId, 128);
        buf.writeUtf(channelLabel, Limits.MAX_DISPLAY_NAME * 4);
        NetCodec.writeSource(buf, source);
        writeTimeline(buf, timeline);
        buf.writeFloat(speakerGain);
        buf.writeVarInt(preset.ordinal());
        buf.writeVarInt(redstoneMode.ordinal());
        buf.writeVarInt(queueSize);
        buf.writeVarInt(queueIndex + 1);
        buf.writeUtf(leaseHolder, Limits.MAX_PLAYER_NAME * 4);
        buf.writeVarInt(leaseGeneration);
        buf.writeVarLong(leaseRemainingMs);
        buf.writeBoolean(leaseIsYours);
        buf.writeVarInt(viewerCount);
        buf.writeVarLong(catalogRevision);
        buf.writeBoolean(locked);
        buf.writeBoolean(youtubeAllowed);
        buf.writeBlockPos(pos);
        buf.writeUtf(dimension, 256);
    }

    public static TvSnapshot decode(FriendlyByteBuf buf) {
        UUID tvId = buf.readUUID();
        long epoch = buf.readLong();
        long revision = buf.readVarLong();
        int generation = buf.readVarInt();
        String model = buf.readUtf(64);
        String displayName = buf.readUtf(Limits.MAX_DISPLAY_NAME * 4);
        String ownerName = buf.readUtf(Limits.MAX_PLAYER_NAME * 4);
        TvRole role = NetCodec.readEnum(buf, TvRole.values());
        int permissionBits = buf.readVarInt();
        boolean publicControls = buf.readBoolean();
        boolean powered = buf.readBoolean();
        int channelNumber = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_CHANNEL_NUMBER);
        String channelId = buf.readUtf(128);
        String channelLabel = buf.readUtf(Limits.MAX_DISPLAY_NAME * 4);
        MediaSource source = NetCodec.readSourceOrFail(buf, MediaSource.Policy.STRUCTURAL);
        TimelineAnchor timeline = readTimeline(buf);
        float gain = buf.readFloat();
        if (!Float.isFinite(gain) || gain < 0 || gain > 1) {
            throw new DecoderException("bad gain");
        }
        VisualPreset preset = NetCodec.readEnum(buf, VisualPreset.values());
        RedstoneMode redstone = NetCodec.readEnum(buf, RedstoneMode.values());
        int queueSize = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE);
        int queueIndex = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE) - 1;
        String leaseHolder = buf.readUtf(Limits.MAX_PLAYER_NAME * 4);
        int leaseGeneration = buf.readVarInt();
        long leaseRemaining = buf.readVarLong();
        boolean leaseIsYours = buf.readBoolean();
        int viewers = NetCodec.readBoundedVarInt(buf, 0, 10_000);
        long catalogRevision = buf.readVarLong();
        boolean locked = buf.readBoolean();
        boolean youtube = buf.readBoolean();
        BlockPos pos = buf.readBlockPos();
        String dimension = buf.readUtf(256);
        return new TvSnapshot(tvId, epoch, revision, generation, model, displayName, ownerName, role, permissionBits, publicControls,
                powered, channelNumber, channelId, channelLabel, source, timeline, gain, preset, redstone, queueSize, queueIndex,
                leaseHolder, leaseGeneration, leaseRemaining, leaseIsYours, viewers, catalogRevision, locked, youtube, pos, dimension);
    }

    public static void writeTimeline(FriendlyByteBuf buf, TimelineAnchor t) {
        buf.writeVarInt(t.state().ordinal());
        buf.writeLong(t.positionMs());
        buf.writeLong(t.anchorServerNanos());
        buf.writeDouble(t.rate());
        buf.writeLong(t.durationMs());
        buf.writeBoolean(t.live());
    }

    public static TimelineAnchor readTimeline(FriendlyByteBuf buf) {
        PlayState state = NetCodec.readEnum(buf, PlayState.values());
        long position = NetCodec.readBoundedLong(buf, 0, Limits.MAX_POSITION_MS);
        long anchor = buf.readLong();
        double rate = NetCodec.readFiniteDouble(buf, 0.25, 4.0);
        long duration = NetCodec.readBoundedLong(buf, -1, Limits.MAX_POSITION_MS);
        boolean live = buf.readBoolean();
        return new TimelineAnchor(state, position, anchor, rate, duration, live);
    }

    /** Applies a delta's changed fields onto this snapshot (the client checks revision ordering first). */
    public TvSnapshot apply(TvDelta d) {
        return new TvSnapshot(tvId, epoch, d.revision(),
                d.has(TvDelta.SOURCE) ? d.sourceGeneration() : sourceGeneration,
                model,
                d.has(TvDelta.NAME) ? d.displayName() : displayName,
                ownerName, yourRole, permissionBits, publicControls,
                d.has(TvDelta.POWER) ? d.powered() : powered,
                d.has(TvDelta.CHANNEL) ? d.channelNumber() : channelNumber,
                d.has(TvDelta.CHANNEL) ? d.channelId() : channelId,
                d.has(TvDelta.CHANNEL) ? d.channelLabel() : channelLabel,
                d.has(TvDelta.SOURCE) ? d.source() : source,
                d.has(TvDelta.TIMELINE) ? d.timeline() : timeline,
                d.has(TvDelta.GAIN) ? d.speakerGain() : speakerGain,
                d.has(TvDelta.PRESET) ? d.preset() : preset,
                d.has(TvDelta.REDSTONE) ? d.redstoneMode() : redstoneMode,
                d.has(TvDelta.QUEUE) ? d.queueSize() : queueSize,
                d.has(TvDelta.QUEUE) ? d.queueIndex() : queueIndex,
                d.has(TvDelta.LEASE) ? d.leaseHolder() : leaseHolder,
                d.has(TvDelta.LEASE) ? d.leaseGeneration() : leaseGeneration,
                d.has(TvDelta.LEASE) ? d.leaseRemainingMs() : leaseRemainingMs,
                d.has(TvDelta.LEASE) ? leaseIsYours && d.leaseHolder().equals(leaseHolder) : leaseIsYours,
                d.has(TvDelta.VIEWERS) ? d.viewerCount() : viewerCount,
                catalogRevision, locked, youtubeAllowed, pos, dimension);
    }
}
