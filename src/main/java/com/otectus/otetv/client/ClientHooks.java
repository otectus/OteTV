package com.otectus.otetv.client;

import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvPublicState;
import com.otectus.otetv.client.media.PlaybackBackend;
import com.otectus.otetv.client.render.TvProfile;
import com.otectus.otetv.client.render.TvProfiles;
import com.otectus.otetv.client.screen.DiagnosticsScreen;
import com.otectus.otetv.client.screen.PairConfirmScreen;
import com.otectus.otetv.client.screen.RemoteScreen;
import com.otectus.otetv.client.screen.TvMenuScreen;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.item.RemoteControlItem;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvDelta;
import com.otectus.otetv.network.TvSnapshot;
import com.otectus.otetv.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single bridge from common code into the client. Common classes reach it only through
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)}, so a dedicated server never loads any client class.
 */
public final class ClientHooks {
    private static final Map<UUID, List<S2C.AclEntry>> ACL = new HashMap<>();

    private ClientHooks() {
    }

    /**
     * Using a TV: a click on the picture of a joined, eligible YouTube session becomes a real input event for the
     * official player (ray-to-plane UV mapping, rotated with the TV); anything else opens the TV menu.
     */
    public static void useTelevision(BlockPos anchor, Vec3 hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(anchor) instanceof TvBlockEntity be) || be.tvId() == null) {
            return;
        }
        UUID id = be.tvId();
        ClientPlaybackSession session = ClientTvManager.get().session(id);
        if (session != null && session.isYouTube() && session.backend() != null && !session.tooSmallForYouTube()
                && !session.isWaitingForLocalResume()) {
            int[] p = screenPoint(be, hit, session.backend());
            if (p != null) {
                session.backend().mouse(Msg.Mouse.DOWN, p[0], p[1], 0, 0, 0);
                session.backend().mouse(Msg.Mouse.UP, p[0], p[1], 0, 0, 0);
                return;
            }
        }
        mc.setScreen(new TvMenuScreen(id));
    }

    /** Maps a world hit position on the TV to browser viewport pixels, or null if outside the picture. */
    static int[] screenPoint(TvBlockEntity be, Vec3 hit, PlaybackBackend backend) {
        TvProfile.Aperture a = TvProfiles.get(be.model(), be.mount()).aperture();
        double lx = hit.x - be.getBlockPos().getX();
        double lz = hit.z - be.getBlockPos().getZ();
        double mx;
        switch (TvGeometry.yRotation(be.facing())) {
            case 90 -> mx = lz;
            case 180 -> mx = 1 - lx;
            case 270 -> mx = 1 - lz;
            default -> mx = lx;
        }
        double px = mx * 16;
        double py = (hit.y - be.getBlockPos().getY()) * 16;
        if (px < a.x0() || px > a.x1() || py < a.y0() || py > a.y1()) {
            return null;
        }
        double u = (a.x1() - px) / a.width();
        double v = (a.y1() - py) / a.height();
        int bw = Math.max(1, backend.status().videoWidth());
        int bh = Math.max(1, backend.status().videoHeight());
        double contentAspect = (double) bw / bh;
        double apertureAspect = a.aspect();
        double cu0 = 0;
        double cv0 = 0;
        double cu1 = 1;
        double cv1 = 1;
        if (contentAspect > apertureAspect) {
            double hFrac = apertureAspect / contentAspect;
            cv0 = (1 - hFrac) / 2;
            cv1 = 1 - cv0;
        } else {
            double wFrac = contentAspect / apertureAspect;
            cu0 = (1 - wFrac) / 2;
            cu1 = 1 - cu0;
        }
        if (u < cu0 || u > cu1 || v < cv0 || v > cv1) {
            return null; // letterbox bars are not part of the player
        }
        return new int[]{(int) ((u - cu0) / (cu1 - cu0) * bw), (int) ((v - cv0) / (cv1 - cv0) * bh)};
    }

    public static void openRemote(InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        RemoteControlItem.Pairing pairing = RemoteControlItem.pairing(mc.player.getItemInHand(hand));
        if (pairing == null) {
            mc.player.displayClientMessage(Component.translatable("otetv.remote.not_paired_hint"), true);
            return;
        }
        mc.setScreen(new RemoteScreen(null, pairing.tv(), hand));
    }

    public static void remoteUsedOnTelevision(InteractionHand hand, BlockPos anchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack stack = mc.player.getItemInHand(hand);
        RemoteControlItem.Pairing pairing = RemoteControlItem.pairing(stack);
        UUID target = ClientTvManager.get().tvAt(anchor);
        if (pairing != null && pairing.tv().equals(target)) {
            mc.setScreen(new RemoteScreen(null, target, hand));
        } else {
            mc.setScreen(new PairConfirmScreen(hand, anchor, target, pairing));
        }
    }

    public static void onTelevisionLoaded(TvBlockEntity be) {
        ClientTvManager.get().onTelevisionLoaded(be);
    }

    public static void onTelevisionGone(TvBlockEntity be, boolean destroyed) {
        ClientTvManager.get().onTelevisionGone(be, destroyed);
    }

    /** Local, optional feedback for confirmed changes: power and channel sounds respect accessibility settings. */
    public static void onPublicStateChanged(TvBlockEntity be, TvPublicState before, TvPublicState after) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || before.tvId() == null || !OteTvClientConfig.STARTUP_SOUNDS.get()) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        if (!before.powered() && after.powered()) {
            boolean crtEra = be.model().ordinal() <= com.otectus.otetv.block.TvModel.CRT.ordinal();
            mc.level.playLocalSound(pos, crtEra ? ModSounds.CRT_STARTUP.get() : ModSounds.POWER_ON.get(), SoundSource.BLOCKS, 0.5f, 1.0f, false);
        } else if (before.powered() && !after.powered()) {
            mc.level.playLocalSound(pos, ModSounds.POWER_OFF.get(), SoundSource.BLOCKS, 0.5f, 1.0f, false);
        } else if (after.powered() && before.channelNumber() != after.channelNumber()) {
            boolean dial = be.model() == com.otectus.otetv.block.TvModel.WOODEN_MONOCHROME || be.model() == com.otectus.otetv.block.TvModel.WOODGRAIN_COLOR;
            mc.level.playLocalSound(pos, dial ? ModSounds.KNOB_TURN.get() : ModSounds.CHANNEL_CLICK.get(), SoundSource.BLOCKS, 0.4f, 1.0f, false);
        }
    }

    public static void onSnapshot(TvSnapshot snapshot) {
        ClientTvManager.get().onSnapshot(snapshot);
    }

    public static void onDelta(TvDelta delta) {
        ClientTvManager.get().onDelta(delta);
    }

    public static void onTiming(S2C.Timing timing) {
        ClientTvManager.get().onTiming(timing);
    }

    public static void onActionResult(S2C.ActionResult result) {
        ClientTvManager.get().onActionResult(result);
    }

    public static void onSessionRemoved(S2C.SessionRemoved removed) {
        ClientTvManager.get().onSessionRemoved(removed);
    }

    public static void onClockReply(S2C.ClockReply reply, long c3) {
        ClientTvManager.get().onClockReply(reply, c3);
    }

    public static void onCatalog(S2C.ChannelCatalog catalog) {
        ClientTvManager.get().onCatalog(catalog);
    }

    public static void onQueuePage(S2C.QueuePage page) {
        ClientTvManager.get().onQueuePage(page);
    }

    public static void onAcl(S2C.AclList list) {
        ACL.put(list.tv(), List.copyOf(list.entries()));
    }

    public static List<S2C.AclEntry> acl(UUID tv) {
        return ACL.getOrDefault(tv, List.of());
    }

    public static void showDiagnostics(String serverSummary) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DiagnosticsScreen(mc.screen, null, serverSummary));
    }

    public static void reset() {
        ACL.clear();
        ClientTvManager.get().reset();
    }
}
