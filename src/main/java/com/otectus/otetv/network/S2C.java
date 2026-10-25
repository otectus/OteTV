package com.otectus.otetv.network;

import com.otectus.otetv.client.ClientHooks;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.sync.TimelineAnchor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-to-client packets. Clients treat all of this as untrusted metadata: a hostile server can send valid-looking
 * values, but it cannot turn on a player's networking, consent, autoplay or local-file access (section 7.1).
 */
public final class S2C {
    private S2C() {
    }

    private static void onClient(Supplier<NetworkEvent.Context> ctx, Runnable clientAction) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> clientAction));
        ctx.get().setPacketHandled(true);
    }

    public record Snapshot(TvSnapshot snapshot) {
        public void encode(FriendlyByteBuf buf) {
            snapshot.encode(buf);
        }

        public static Snapshot decode(FriendlyByteBuf buf) {
            return new Snapshot(TvSnapshot.decode(buf));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onSnapshot(snapshot));
        }
    }

    public record Delta(TvDelta delta) {
        public void encode(FriendlyByteBuf buf) {
            delta.encode(buf);
        }

        public static Delta decode(FriendlyByteBuf buf) {
            return new Delta(TvDelta.decode(buf));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onDelta(delta));
        }
    }

    /** Periodic anchor refresh for subscribers (about every two seconds while playing); not a revision change. */
    public record Timing(UUID tv, long epoch, long revision, int sourceGeneration, TimelineAnchor timeline) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeLong(epoch);
            buf.writeVarLong(revision);
            buf.writeVarInt(sourceGeneration);
            TvSnapshot.writeTimeline(buf, timeline);
        }

        public static Timing decode(FriendlyByteBuf buf) {
            return new Timing(buf.readUUID(), buf.readLong(), buf.readVarLong(), buf.readVarInt(), TvSnapshot.readTimeline(buf));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onTiming(this));
        }
    }

    public record ActionResult(int actionId, boolean accepted, RejectReason reason, long revision, String detail) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(actionId);
            buf.writeBoolean(accepted);
            buf.writeVarInt(reason.ordinal());
            buf.writeVarLong(revision);
            buf.writeUtf(detail, Limits.MAX_DETAIL);
        }

        public static ActionResult decode(FriendlyByteBuf buf) {
            return new ActionResult(buf.readVarInt(), buf.readBoolean(), NetCodec.readEnum(buf, RejectReason.values()),
                    buf.readVarLong(), buf.readUtf(Limits.MAX_DETAIL));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onActionResult(this));
        }
    }

    /** The TV was broken, replaced, unloaded or reset: invalidate every local session for it. */
    public record SessionRemoved(UUID tv, long epoch, boolean destroyed) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeLong(epoch);
            buf.writeBoolean(destroyed);
        }

        public static SessionRemoved decode(FriendlyByteBuf buf) {
            return new SessionRemoved(buf.readUUID(), buf.readLong(), buf.readBoolean());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onSessionRemoved(this));
        }
    }

    public record ClockReply(long c0, long s1, long s2, long epoch) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeLong(c0);
            buf.writeLong(s1);
            buf.writeLong(s2);
            buf.writeLong(epoch);
        }

        public static ClockReply decode(FriendlyByteBuf buf) {
            return new ClockReply(buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            long c3 = System.nanoTime();
            // Timestamp on arrival, before queueing onto the client thread.
            onClient(ctx, () -> ClientHooks.onClockReply(this, c3));
        }
    }

    public record ChannelEntry(int number, String id, String displayName, boolean translatable, String description,
                               MediaSource source, boolean enabled) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(number);
            buf.writeUtf(id, 128);
            buf.writeUtf(displayName, 128 * 4);
            buf.writeBoolean(translatable);
            buf.writeUtf(description, Limits.MAX_DESCRIPTION * 4);
            NetCodec.writeSource(buf, source);
            buf.writeBoolean(enabled);
        }

        static ChannelEntry decode(FriendlyByteBuf buf) {
            int number = NetCodec.readBoundedVarInt(buf, 1, Limits.MAX_CHANNEL_NUMBER);
            String id = buf.readUtf(128);
            String name = buf.readUtf(128 * 4);
            boolean translatable = buf.readBoolean();
            String description = buf.readUtf(Limits.MAX_DESCRIPTION * 4);
            MediaSource source = NetCodec.readSourceOrFail(buf, MediaSource.Policy.STRUCTURAL);
            if (source == null) {
                throw new io.netty.handler.codec.DecoderException("channel without source");
            }
            return new ChannelEntry(number, id, name, translatable, description, source, buf.readBoolean());
        }
    }

    /** One bounded page of the server-approved catalog (section 18.3). */
    public record ChannelCatalog(long revision, int page, int pageCount, List<ChannelEntry> entries) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarLong(revision);
            buf.writeVarInt(page);
            buf.writeVarInt(pageCount);
            buf.writeVarInt(entries.size());
            entries.forEach(e -> e.encode(buf));
        }

        public static ChannelCatalog decode(FriendlyByteBuf buf) {
            long revision = buf.readVarLong();
            int page = NetCodec.readBoundedVarInt(buf, 0, 64);
            int pages = NetCodec.readBoundedVarInt(buf, 0, 64);
            int count = NetCodec.readBoundedVarInt(buf, 0, Limits.CATALOG_PAGE_SIZE);
            List<ChannelEntry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(ChannelEntry.decode(buf));
            }
            return new ChannelCatalog(revision, page, pages, list);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onCatalog(this));
        }
    }

    public record QueuePage(UUID tv, long revision, int index, List<MediaSource> entries) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarLong(revision);
            buf.writeVarInt(index + 1);
            buf.writeVarInt(entries.size());
            for (MediaSource s : entries) {
                NetCodec.writeSource(buf, s);
            }
        }

        public static QueuePage decode(FriendlyByteBuf buf) {
            UUID tv = buf.readUUID();
            long revision = buf.readVarLong();
            int index = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE) - 1;
            int count = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_QUEUE);
            List<MediaSource> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MediaSource s = NetCodec.readSourceOrFail(buf, MediaSource.Policy.STRUCTURAL);
                if (s != null) {
                    list.add(s);
                }
            }
            return new QueuePage(tv, revision, index, list);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onQueuePage(this));
        }
    }

    public record AclEntry(UUID player, String name, com.otectus.otetv.core.protocol.TvRole role) {
    }

    /** The trusted-player list, sent only to the owner or an operator. */
    public record AclList(UUID tv, long revision, List<AclEntry> entries) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarLong(revision);
            buf.writeVarInt(entries.size());
            for (AclEntry e : entries) {
                buf.writeUUID(e.player());
                buf.writeUtf(e.name(), Limits.MAX_PLAYER_NAME * 4);
                buf.writeVarInt(e.role().ordinal());
            }
        }

        public static AclList decode(FriendlyByteBuf buf) {
            UUID tv = buf.readUUID();
            long revision = buf.readVarLong();
            int n = NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_TRUSTED);
            List<AclEntry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new AclEntry(buf.readUUID(), buf.readUtf(Limits.MAX_PLAYER_NAME * 4),
                        NetCodec.readEnum(buf, com.otectus.otetv.core.protocol.TvRole.values())));
            }
            return new AclList(tv, revision, list);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.onAcl(this));
        }
    }

    /** Asks the client to print its own local runtime details for /otetv diagnostics. Carries no data. */
    public record ShowDiagnostics(@Nullable String serverSummary) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(serverSummary == null ? "" : serverSummary, 4096);
        }

        public static ShowDiagnostics decode(FriendlyByteBuf buf) {
            return new ShowDiagnostics(buf.readUtf(4096));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onClient(ctx, () -> ClientHooks.showDiagnostics(serverSummary));
        }
    }
}
