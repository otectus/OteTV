package com.otectus.otetv.network;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.session.TvNetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-to-server packets (section 9.3). Decoders bound every field; handlers run on the server thread and never
 * trust client-supplied ownership, coordinates or revisions as authority. None of these can cause chunk loading.
 */
public final class C2S {
    private C2S() {
    }

    private static void onServer(Supplier<NetworkEvent.Context> ctx, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            action.accept(player);
        }
        ctx.get().setPacketHandled(true);
    }

    public record RequestSnapshot(UUID tv, long knownRevision) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarLong(knownRevision);
        }

        public static RequestSnapshot decode(FriendlyByteBuf buf) {
            return new RequestSnapshot(buf.readUUID(), buf.readVarLong());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.requestSnapshot(p, this));
        }
    }

    /** Capability flags are coarse booleans (has direct-media runtime, has browser runtime); never account data. */
    public record JoinViewing(UUID tv, int capabilityFlags) {
        public static final int DIRECT_RUNTIME = 1;
        public static final int BROWSER_RUNTIME = 2;

        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarInt(capabilityFlags);
        }

        public static JoinViewing decode(FriendlyByteBuf buf) {
            return new JoinViewing(buf.readUUID(), NetCodec.readBoundedVarInt(buf, 0, 255));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.join(p, this));
        }
    }

    public record LeaveViewing(UUID tv) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
        }

        public static LeaveViewing decode(FriendlyByteBuf buf) {
            return new LeaveViewing(buf.readUUID());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.leave(p, this));
        }
    }

    public record AcquireControl(UUID tv, int expectedLeaseGeneration) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarInt(expectedLeaseGeneration);
        }

        public static AcquireControl decode(FriendlyByteBuf buf) {
            return new AcquireControl(buf.readUUID(), buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.acquire(p, this));
        }
    }

    public record ReleaseControl(UUID tv, int leaseGeneration) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarInt(leaseGeneration);
        }

        public static ReleaseControl decode(FriendlyByteBuf buf) {
            return new ReleaseControl(buf.readUUID(), buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.release(p, this));
        }
    }

    /**
     * A typed, versioned shared action with bounded arguments. Which argument slots are meaningful depends on the
     * action type; the server validates them per type.
     */
    public record TvAction(UUID tv, long expectedRevision, int actionId, TvActionType type, long longArg, int intArg,
                           String stringArg, @Nullable MediaSource source, @Nullable UUID uuidArg) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarLong(expectedRevision);
            buf.writeVarInt(actionId);
            buf.writeVarInt(type.ordinal());
            buf.writeLong(longArg);
            buf.writeInt(intArg);
            buf.writeUtf(stringArg, Limits.MAX_DISPLAY_NAME * 4);
            NetCodec.writeSource(buf, source);
            buf.writeBoolean(uuidArg != null);
            if (uuidArg != null) {
                buf.writeUUID(uuidArg);
            }
        }

        public static TvAction decode(FriendlyByteBuf buf) {
            UUID tv = buf.readUUID();
            long revision = buf.readVarLong();
            int actionId = buf.readVarInt();
            TvActionType type = NetCodec.readEnum(buf, TvActionType.values());
            long longArg = buf.readLong();
            int intArg = buf.readInt();
            String stringArg = buf.readUtf(Limits.MAX_DISPLAY_NAME * 4);
            // Structure only here; the server applies its configured source policy when handling.
            MediaSource source = NetCodec.readSourceOrFail(buf, MediaSource.Policy.STRUCTURAL);
            UUID uuid = buf.readBoolean() ? buf.readUUID() : null;
            return new TvAction(tv, revision, actionId, type, longArg, intArg, stringArg, source, uuid);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.action(p, this));
        }
    }

    /** Coarse local state for diagnostics and duration hints; at most one per second per TV (section 9.3). */
    public record ViewerStatus(UUID tv, int generation, LocalPlaybackState state, long positionMs, long durationMs, boolean live) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
            buf.writeVarInt(generation);
            buf.writeVarInt(state.ordinal());
            buf.writeLong(positionMs);
            buf.writeLong(durationMs);
            buf.writeBoolean(live);
        }

        public static ViewerStatus decode(FriendlyByteBuf buf) {
            return new ViewerStatus(buf.readUUID(), buf.readVarInt(), NetCodec.readEnum(buf, LocalPlaybackState.values()),
                    NetCodec.readBoundedLong(buf, -1, Limits.MAX_POSITION_MS), NetCodec.readBoundedLong(buf, -1, Limits.MAX_POSITION_MS),
                    buf.readBoolean());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.viewerStatus(p, this));
        }
    }

    /** NTP-style probe; handled on the network thread so server timestamps are not delayed by the tick loop. */
    public record ClockProbe(long c0) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeLong(c0);
        }

        public static ClockProbe decode(FriendlyByteBuf buf) {
            return new ClockProbe(buf.readLong());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            long s1 = TvNetworkHandler.serverNanos();
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                TvNetworkHandler.clockProbe(player, c0, s1);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    public record RequestCatalog(int page) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(page);
        }

        public static RequestCatalog decode(FriendlyByteBuf buf) {
            return new RequestCatalog(NetCodec.readBoundedVarInt(buf, 0, Limits.MAX_CHANNELS / Limits.CATALOG_PAGE_SIZE + 1));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.catalog(p, page));
        }
    }

    public record RequestQueue(UUID tv) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
        }

        public static RequestQueue decode(FriendlyByteBuf buf) {
            return new RequestQueue(buf.readUUID());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.queue(p, tv));
        }
    }

    /** Owner/operator only: the TV's trusted-player list, which snapshots never carry. */
    public record RequestAcl(UUID tv) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(tv);
        }

        public static RequestAcl decode(FriendlyByteBuf buf) {
            return new RequestAcl(buf.readUUID());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.acl(p, tv));
        }
    }

    public record PairRemote(InteractionHand hand, BlockPos pos) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeEnum(hand);
            buf.writeBlockPos(pos);
        }

        public static PairRemote decode(FriendlyByteBuf buf) {
            return new PairRemote(NetCodec.readEnum(buf, InteractionHand.values()), buf.readBlockPos());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.pair(p, hand, pos));
        }
    }

    public record UnpairRemote(InteractionHand hand) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeEnum(hand);
        }

        public static UnpairRemote decode(FriendlyByteBuf buf) {
            return new UnpairRemote(NetCodec.readEnum(buf, InteractionHand.values()));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            onServer(ctx, p -> TvNetworkHandler.unpair(p, hand));
        }
    }
}
