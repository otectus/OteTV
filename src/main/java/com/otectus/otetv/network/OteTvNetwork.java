package com.otectus.otetv.network;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.OteTvCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The Forge {@link SimpleChannel} with an explicit protocol version and per-message direction (section 9.3). A
 * mismatched protocol refuses the connection instead of exchanging incompatible packets.
 */
public final class OteTvNetwork {
    public static final String PROTOCOL = Integer.toString(OteTvCore.NETWORK_PROTOCOL_VERSION);
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(OteTV.id("main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private OteTvNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        int id = 0;
        CHANNEL.messageBuilder(C2S.RequestSnapshot.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.RequestSnapshot::encode).decoder(C2S.RequestSnapshot::decode).consumerMainThread(handler(C2S.RequestSnapshot::handle)).add();
        CHANNEL.messageBuilder(C2S.JoinViewing.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.JoinViewing::encode).decoder(C2S.JoinViewing::decode).consumerMainThread(handler(C2S.JoinViewing::handle)).add();
        CHANNEL.messageBuilder(C2S.LeaveViewing.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.LeaveViewing::encode).decoder(C2S.LeaveViewing::decode).consumerMainThread(handler(C2S.LeaveViewing::handle)).add();
        CHANNEL.messageBuilder(C2S.AcquireControl.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.AcquireControl::encode).decoder(C2S.AcquireControl::decode).consumerMainThread(handler(C2S.AcquireControl::handle)).add();
        CHANNEL.messageBuilder(C2S.ReleaseControl.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.ReleaseControl::encode).decoder(C2S.ReleaseControl::decode).consumerMainThread(handler(C2S.ReleaseControl::handle)).add();
        CHANNEL.messageBuilder(C2S.TvAction.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.TvAction::encode).decoder(C2S.TvAction::decode).consumerMainThread(handler(C2S.TvAction::handle)).add();
        CHANNEL.messageBuilder(C2S.ViewerStatus.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.ViewerStatus::encode).decoder(C2S.ViewerStatus::decode).consumerMainThread(handler(C2S.ViewerStatus::handle)).add();
        CHANNEL.messageBuilder(C2S.ClockProbe.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.ClockProbe::encode).decoder(C2S.ClockProbe::decode).consumerNetworkThread(handler(C2S.ClockProbe::handle)).add();
        CHANNEL.messageBuilder(C2S.RequestCatalog.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.RequestCatalog::encode).decoder(C2S.RequestCatalog::decode).consumerMainThread(handler(C2S.RequestCatalog::handle)).add();
        CHANNEL.messageBuilder(C2S.RequestQueue.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.RequestQueue::encode).decoder(C2S.RequestQueue::decode).consumerMainThread(handler(C2S.RequestQueue::handle)).add();
        CHANNEL.messageBuilder(C2S.PairRemote.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.PairRemote::encode).decoder(C2S.PairRemote::decode).consumerMainThread(handler(C2S.PairRemote::handle)).add();
        CHANNEL.messageBuilder(C2S.UnpairRemote.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.UnpairRemote::encode).decoder(C2S.UnpairRemote::decode).consumerMainThread(handler(C2S.UnpairRemote::handle)).add();

        CHANNEL.messageBuilder(C2S.RequestAcl.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2S.RequestAcl::encode).decoder(C2S.RequestAcl::decode).consumerMainThread(handler(C2S.RequestAcl::handle)).add();
        CHANNEL.messageBuilder(S2C.AclList.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.AclList::encode).decoder(S2C.AclList::decode).consumerNetworkThread(handler(S2C.AclList::handle)).add();

        CHANNEL.messageBuilder(S2C.Snapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.Snapshot::encode).decoder(S2C.Snapshot::decode).consumerNetworkThread(handler(S2C.Snapshot::handle)).add();
        CHANNEL.messageBuilder(S2C.Delta.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.Delta::encode).decoder(S2C.Delta::decode).consumerNetworkThread(handler(S2C.Delta::handle)).add();
        CHANNEL.messageBuilder(S2C.Timing.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.Timing::encode).decoder(S2C.Timing::decode).consumerNetworkThread(handler(S2C.Timing::handle)).add();
        CHANNEL.messageBuilder(S2C.ActionResult.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.ActionResult::encode).decoder(S2C.ActionResult::decode).consumerNetworkThread(handler(S2C.ActionResult::handle)).add();
        CHANNEL.messageBuilder(S2C.SessionRemoved.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.SessionRemoved::encode).decoder(S2C.SessionRemoved::decode).consumerNetworkThread(handler(S2C.SessionRemoved::handle)).add();
        CHANNEL.messageBuilder(S2C.ClockReply.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.ClockReply::encode).decoder(S2C.ClockReply::decode).consumerNetworkThread(handler(S2C.ClockReply::handle)).add();
        CHANNEL.messageBuilder(S2C.ChannelCatalog.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.ChannelCatalog::encode).decoder(S2C.ChannelCatalog::decode).consumerNetworkThread(handler(S2C.ChannelCatalog::handle)).add();
        CHANNEL.messageBuilder(S2C.QueuePage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.QueuePage::encode).decoder(S2C.QueuePage::decode).consumerNetworkThread(handler(S2C.QueuePage::handle)).add();
        CHANNEL.messageBuilder(S2C.ShowDiagnostics.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2C.ShowDiagnostics::encode).decoder(S2C.ShowDiagnostics::decode).consumerNetworkThread(handler(S2C.ShowDiagnostics::handle)).add();
    }

    /** Pins the handler's functional type; consumerNetworkThread is overloaded. */
    private static <M> BiConsumer<M, Supplier<NetworkEvent.Context>> handler(BiConsumer<M, Supplier<NetworkEvent.Context>> h) {
        return h;
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendTo(Collection<ServerPlayer> players, Object message) {
        for (ServerPlayer p : players) {
            sendTo(p, message);
        }
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
