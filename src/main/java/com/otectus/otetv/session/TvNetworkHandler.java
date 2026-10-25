package com.otectus.otetv.session;

import com.otectus.otetv.advancement.ModCriteria;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.channel.ChannelCatalog;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.item.RemoteControlItem;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvDelta;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Server-side packet handling. Every request re-checks: the TV is loaded (never loaded on demand), same dimension,
 * distance, role, lease and revision (section 6.3). Rejections carry a reason the client can show.
 */
public final class TvNetworkHandler {
    private TvNetworkHandler() {
    }

    public static long serverNanos() {
        TvSessionService svc = TvSessionService.get();
        return svc == null ? 0 : svc.now();
    }

    private record Resolved(TvSessionService svc, TvSession session, TvBlockEntity be) {
    }

    @Nullable
    private static Resolved resolve(ServerPlayer player, UUID tv, double range, int actionIdForReply) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null) {
            return null;
        }
        TvSession session = svc.session(tv);
        if (session == null) {
            reply(player, actionIdForReply, RejectReason.NOT_FOUND, 0);
            return null;
        }
        if (player.level().dimension() != session.dimension()) {
            reply(player, actionIdForReply, RejectReason.WRONG_DIMENSION, session.revision());
            return null;
        }
        TvBlockEntity be = svc.blockEntity(session);
        if (be == null) {
            reply(player, actionIdForReply, RejectReason.NOT_LOADED, session.revision());
            return null;
        }
        if (TvSessionService.distanceToAssembly(player, be) > range) {
            reply(player, actionIdForReply, RejectReason.OUT_OF_RANGE, session.revision());
            return null;
        }
        return new Resolved(svc, session, be);
    }

    private static void reply(ServerPlayer player, int actionId, RejectReason reason, long revision) {
        if (actionId >= 0) {
            OteTvNetwork.sendTo(player, new S2C.ActionResult(actionId, reason == RejectReason.NONE, reason, revision, ""));
        } else if (reason == RejectReason.NOT_FOUND || reason == RejectReason.NOT_LOADED || reason == RejectReason.OUT_OF_RANGE
                || reason == RejectReason.WRONG_DIMENSION) {
            // Snapshot requests get an explicit "unavailable" result so remotes can show "TV unavailable" (section 6.1).
            OteTvNetwork.sendTo(player, new S2C.ActionResult(-1, false, reason, revision, ""));
        }
    }

    private static double viewerRange() {
        return OteTvServerConfig.VIEWER_RANGE.get();
    }

    private static double remoteRange() {
        return OteTvServerConfig.REMOTE_RANGE.get();
    }

    public static void requestSnapshot(ServerPlayer player, C2S.RequestSnapshot msg) {
        Resolved r = resolve(player, msg.tv(), viewerRange(), -1);
        if (r != null) {
            r.svc.sendSnapshot(player, r.session, r.be);
        }
    }

    public static void join(ServerPlayer player, C2S.JoinViewing msg) {
        Resolved r = resolve(player, msg.tv(), viewerRange(), -1);
        if (r == null || !r.svc.hasPermission(player, r.be, TvPermission.JOIN)) {
            return;
        }
        boolean added = r.session.subscribers.add(player.getUUID());
        r.svc.sendSnapshot(player, r.session, r.be);
        if (added) {
            r.svc.broadcastDelta(r.session, r.be, TvDelta.VIEWERS, false);
        }
    }

    public static void leave(ServerPlayer player, C2S.LeaveViewing msg) {
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null ? null : svc.session(msg.tv());
        if (session == null) {
            return;
        }
        if (session.subscribers.remove(player.getUUID())) {
            session.reports.remove(player.getUUID());
            TvBlockEntity be = svc.blockEntity(session);
            if (be != null) {
                svc.broadcastDelta(session, be, TvDelta.VIEWERS, false);
            }
        }
    }

    public static void acquire(ServerPlayer player, C2S.AcquireControl msg) {
        Resolved r = resolve(player, msg.tv(), remoteRange(), -1);
        if (r == null) {
            return;
        }
        TvRole role = TvAccess.role(player, r.be.state());
        if (!TvAccess.permissions(player, r.be.state()).contains(TvPermission.PLAYBACK)) {
            OteTvNetwork.sendTo(player, new S2C.ActionResult(-2, false, RejectReason.NO_PERMISSION, r.session.revision(), ""));
            return;
        }
        if (!r.svc.ensureLease(player, r.session, r.be, role)) {
            OteTvNetwork.sendTo(player, new S2C.ActionResult(-2, false, RejectReason.LEASE_HELD, r.session.revision(),
                    r.session.lease() == null ? "" : r.session.lease().holderName()));
            return;
        }
        r.svc.sendSnapshot(player, r.session, r.be);
    }

    public static void release(ServerPlayer player, C2S.ReleaseControl msg) {
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null ? null : svc.session(msg.tv());
        TvBlockEntity be = session == null ? null : svc.blockEntity(session);
        if (be != null) {
            svc.releaseLease(player, session, be, msg.leaseGeneration());
        }
    }

    public static void action(ServerPlayer player, C2S.TvAction msg) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null) {
            return;
        }
        if (!svc.allowAction(player)) {
            OteTvNetwork.sendTo(player, new S2C.ActionResult(msg.actionId(), false, RejectReason.RATE_LIMITED, 0, ""));
            return;
        }
        Resolved r = resolve(player, msg.tv(), remoteRange(), msg.actionId());
        if (r == null) {
            return;
        }
        MediaSource source = msg.source();
        if (source != null) {
            // Packets were decoded structurally; apply this server's policy now.
            ServerSourcePolicy.Verdict verdict = ServerSourcePolicy.check(source);
            if (!verdict.allowed()) {
                OteTvNetwork.sendTo(player, new S2C.ActionResult(msg.actionId(), false, verdict.reason(), r.session.revision(), verdict.detail()));
                return;
            }
        }
        TvActions.Outcome outcome = TvActions.perform(r.svc, r.session, r.be, player, msg.type(), msg.expectedRevision(),
                new TvActions.Args(msg.longArg(), msg.intArg(), msg.stringArg(), source, msg.uuidArg()));
        OteTvNetwork.sendTo(player, new S2C.ActionResult(msg.actionId(), outcome.accepted(), outcome.reason(), r.session.revision(), outcome.detail()));
        if (outcome.reason() == RejectReason.STALE_REVISION) {
            r.svc.sendSnapshot(player, r.session, r.be);
        }
    }

    public static void viewerStatus(ServerPlayer player, C2S.ViewerStatus msg) {
        TvSessionService svc = TvSessionService.get();
        TvSession session = svc == null ? null : svc.session(msg.tv());
        if (session == null || !session.subscribers.contains(player.getUUID())) {
            return;
        }
        long now = svc.now();
        Long last = session.lastReportNanos.get(player.getUUID());
        if (last != null && now - last < Limits.VIEWER_REPORT_MIN_INTERVAL_MS * 1_000_000L) {
            return;
        }
        session.lastReportNanos.put(player.getUUID(), now);
        if (msg.generation() != session.sourceGeneration) {
            return; // stale report from a previous source
        }
        session.reports.put(player.getUUID(), new TvSession.ViewerReport(msg.generation(), msg.state(), msg.positionMs(), msg.durationMs(), now));
        TvBlockEntity be = svc.blockEntity(session);
        if (be == null) {
            return;
        }
        MediaSource source = be.state().source();
        if (msg.state() == LocalPlaybackState.PLAYING && source != null && source.type() == SourceType.BUNDLED) {
            ModCriteria.PLAYED_DEMO.trigger(player);
        }
        // Only trusted roles may refine duration/liveness or end the item; viewers cannot skip someone's queue.
        TvRole role = TvAccess.role(player, be.state());
        boolean trusted = role.atLeast(TvRole.EDITOR) || (session.lease != null && session.lease.heldBy(player.getUUID()));
        if (!trusted) {
            return;
        }
        boolean live = msg.live();
        long duration = live ? -1 : msg.durationMs();
        if (duration != session.timeline.durationMs() || live != session.timeline.live()) {
            session.timeline = session.timeline.withDuration(duration, live);
            be.state().setDurationHint(duration);
            svc.broadcastDelta(session, be, TvDelta.TIMELINE, false);
        }
        if (msg.state() == LocalPlaybackState.ENDED && session.timeline.state() == PlayState.PLAYING && !live) {
            if (!TvActions.advanceQueue(svc, session, be)) {
                session.timeline = session.timeline.withState(PlayState.STOPPED, 0, now);
                be.state().setCursor(0, false);
                svc.broadcastDelta(session, be, TvDelta.TIMELINE, false);
                be.markDurableChange(true);
            }
        }
    }

    public static void clockProbe(ServerPlayer player, long c0, long s1) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null || !svc.allowClockProbe(player)) {
            return;
        }
        OteTvNetwork.sendTo(player, new S2C.ClockReply(c0, s1, svc.now(), svc.epoch()));
    }

    public static void catalog(ServerPlayer player, int page) {
        ChannelCatalog catalog = ChannelCatalog.get();
        if (catalog != null) {
            OteTvNetwork.sendTo(player, catalog.page(page));
        }
    }

    public static void queue(ServerPlayer player, UUID tv) {
        Resolved r = resolve(player, tv, viewerRange(), -1);
        if (r != null) {
            OteTvNetwork.sendTo(player, new S2C.QueuePage(tv, r.session.revision(), r.be.state().queueIndex(), r.be.state().queue()));
        }
    }

    public static void acl(ServerPlayer player, UUID tv) {
        Resolved r = resolve(player, tv, viewerRange(), -1);
        if (r == null || !r.svc.hasPermission(player, r.be, TvPermission.ACL)) {
            return;
        }
        java.util.List<S2C.AclEntry> entries = new java.util.ArrayList<>();
        r.be.state().trusted().forEach((id, trust) -> entries.add(new S2C.AclEntry(id, trust.name(), trust.role())));
        OteTvNetwork.sendTo(player, new S2C.AclList(tv, r.session.revision(), entries));
    }

    /** Pairing writes identity and labels into the remote; it grants no permission (section 6.1). */
    public static void pair(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof RemoteControlItem)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        TvBlockEntity be = TvSessionService.findController(level, pos);
        if (be == null || be.tvId() == null || TvSessionService.distanceToAssembly(player, be) > 8.0) {
            player.displayClientMessage(Component.translatable("otetv.remote.pair_failed"), true);
            return;
        }
        RemoteControlItem.pair(stack, player, be);
    }

    public static void unpair(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof RemoteControlItem) {
            RemoteControlItem.unpair(stack);
            player.displayClientMessage(Component.translatable("otetv.remote.unpaired"), true);
        }
    }
}
