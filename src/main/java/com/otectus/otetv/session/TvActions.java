package com.otectus.otetv.session;

import com.mojang.authlib.GameProfile;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.api.TvLifecycleEvent;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.channel.ChannelCatalog;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.protocol.RedstoneMode;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.protocol.VisualPreset;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.core.sync.TimelineAnchor;
import com.otectus.otetv.core.text.TextSanitizer;
import com.otectus.otetv.network.TvDelta;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies typed shared actions under server authority (section 9.4). Every mutation re-checks permission, lease and
 * revision; toggles never cross the network. Packets, commands, redstone and the extension API all funnel here, so
 * no caller has a "bypass validation" path (section 20.3).
 */
public final class TvActions {
    public record Args(long longArg, int intArg, String stringArg, @Nullable MediaSource source, @Nullable UUID uuidArg) {
        public static final Args NONE = new Args(0, 0, "", null, null);

        public static Args ofLong(long v) {
            return new Args(v, 0, "", null, null);
        }

        public static Args ofInt(int v) {
            return new Args(0, v, "", null, null);
        }

        public static Args ofString(String s, int v) {
            return new Args(0, v, s, null, null);
        }

        public static Args ofSource(MediaSource s) {
            return new Args(0, 0, "", s, null);
        }

        public static Args ofUuid(UUID id) {
            return new Args(0, 0, "", null, id);
        }
    }

    public record Outcome(RejectReason reason, String detail) {
        public static final Outcome OK = new Outcome(RejectReason.NONE, "");

        public boolean accepted() {
            return reason == RejectReason.NONE;
        }

        static Outcome reject(RejectReason reason) {
            return new Outcome(reason, "");
        }

        static Outcome reject(RejectReason reason, String detail) {
            return new Outcome(reason, detail);
        }
    }

    private static final Set<TvPermission> LEASED = EnumSet.of(TvPermission.POWER, TvPermission.PLAYBACK, TvPermission.CHANNEL,
            TvPermission.SOURCE, TvPermission.QUEUE_EDIT);
    private static final long MAX_RELATIVE_SEEK_MS = 3_600_000L;

    private TvActions() {
    }

    /**
     * @param player the acting player, or null for redstone and server-internal callers
     * @param expectedRevision the client's revision, or -1 when the caller is server-side
     */
    public static Outcome perform(TvSessionService svc, TvSession session, TvBlockEntity be, @Nullable ServerPlayer player,
                                  TvActionType type, long expectedRevision, Args args) {
        TvState state = be.state();
        if (state.isLocked()) {
            return Outcome.reject(RejectReason.INCOMPATIBLE_DATA);
        }
        TvRole role = player == null ? TvRole.OPERATOR : TvAccess.role(player, state);
        if (player != null) {
            Set<TvPermission> permissions = TvAccess.permissions(player, state);
            if (!permissions.contains(type.permission())) {
                return Outcome.reject(RejectReason.NO_PERMISSION);
            }
            if (expectedRevision >= 0 && expectedRevision != session.revision && !type.idempotent()) {
                return Outcome.reject(RejectReason.STALE_REVISION);
            }
            if (LEASED.contains(type.permission()) && !svc.ensureLease(player, session, be, role)) {
                return Outcome.reject(RejectReason.LEASE_HELD, session.lease == null ? "" : session.lease.holderName());
            }
        }
        Outcome outcome = apply(svc, session, be, player, role, type, args);
        if (outcome.accepted()) {
            OteTV.LOGGER.info(OteTV.AUDIT, "{} {} on television {} ({} {})", player == null ? "server" : player.getGameProfile().getName(),
                    type, session.tvId, session.dimension.location(), session.pos.toShortString());
            MinecraftForge.EVENT_BUS.post(new TvLifecycleEvent.StateChanged(session.tvId, type, player == null ? null : player.getUUID()));
        }
        return outcome;
    }

    private static long now(TvSessionService svc) {
        return svc.now();
    }

    private static long lead(MediaSource source) {
        // Direct media gets a scheduled start so every client begins together; YouTube is best effort (section 10.3).
        return source != null && source.type() == SourceType.YOUTUBE ? 250_000_000L : Limits.SCHEDULED_START_LEAD_MS * 1_000_000L;
    }

    private static Outcome apply(TvSessionService svc, TvSession session, TvBlockEntity be, @Nullable ServerPlayer player, TvRole role,
                                 TvActionType type, Args args) {
        TvState s = be.state();
        long now = now(svc);
        TimelineAnchor t = session.timeline;
        switch (type) {
            case SET_POWER -> {
                boolean on = args.intArg() != 0;
                if (s.powered() == on) {
                    return Outcome.OK;
                }
                s.setPowered(on);
                int mask = TvDelta.POWER;
                if (!on && t.state() == PlayState.PLAYING) {
                    long position = t.targetPositionMs(now);
                    session.timeline = t.withState(PlayState.PAUSED, position, now);
                    s.setCursor(position, false);
                    mask |= TvDelta.TIMELINE;
                }
                return commit(svc, session, be, mask, true);
            }
            case PLAY -> {
                if (!s.powered()) {
                    return Outcome.reject(RejectReason.NOT_POWERED);
                }
                if (s.source() == null) {
                    return Outcome.reject(RejectReason.NO_SOURCE);
                }
                if (t.state() == PlayState.PLAYING) {
                    return Outcome.OK;
                }
                if (svc.activeSessionsIn(session.dimension) >= OteTvServerConfig.MAX_ACTIVE_SESSIONS_PER_DIMENSION.get()) {
                    return Outcome.reject(RejectReason.LIMIT_REACHED);
                }
                long start = t.state() == PlayState.STOPPED ? initialPosition(s.source()) : t.positionMs();
                session.timeline = t.withState(PlayState.PLAYING, t.live() ? 0 : start, now + lead(s.source()));
                s.setCursor(start, true);
                return commit(svc, session, be, TvDelta.TIMELINE, true);
            }
            case PAUSE -> {
                if (t.state() != PlayState.PLAYING) {
                    return Outcome.OK;
                }
                long position = t.targetPositionMs(now);
                session.timeline = t.withState(t.live() ? PlayState.STOPPED : PlayState.PAUSED, t.live() ? 0 : position, now);
                s.setCursor(t.live() ? 0 : position, false);
                return commit(svc, session, be, TvDelta.TIMELINE, true);
            }
            case STOP -> {
                if (t.state() == PlayState.STOPPED) {
                    return Outcome.OK;
                }
                session.timeline = t.withState(PlayState.STOPPED, 0, now);
                s.setCursor(0, false);
                return commit(svc, session, be, TvDelta.TIMELINE, true);
            }
            case SEEK_ABSOLUTE, SEEK_RELATIVE -> {
                if (s.source() == null) {
                    return Outcome.reject(RejectReason.NO_SOURCE);
                }
                if (t.live()) {
                    return Outcome.reject(RejectReason.NOT_SEEKABLE);
                }
                long target;
                if (type == TvActionType.SEEK_ABSOLUTE) {
                    target = args.longArg();
                } else {
                    if (Math.abs(args.longArg()) > MAX_RELATIVE_SEEK_MS) {
                        return Outcome.reject(RejectReason.INVALID_ARGUMENT);
                    }
                    target = t.targetPositionMs(now) + args.longArg();
                }
                target = Math.max(0, Math.min(target, t.durationMs() > 0 ? t.durationMs() : Limits.MAX_POSITION_MS));
                long anchorTime = t.state() == PlayState.PLAYING ? now + lead(s.source()) : now;
                session.timeline = t.withState(t.state() == PlayState.STOPPED ? PlayState.PAUSED : t.state(), target, anchorTime);
                s.setCursor(target, t.state() == PlayState.PLAYING);
                return commit(svc, session, be, TvDelta.TIMELINE, true);
            }
            case SET_SPEAKER_GAIN -> {
                float gain = Math.max(0, Math.min(1000, args.longArg())) / 1000f;
                s.setSpeakerGain(gain);
                return commit(svc, session, be, TvDelta.GAIN, true);
            }
            case SELECT_CHANNEL, CHANNEL_STEP -> {
                if (!s.powered()) {
                    return Outcome.reject(RejectReason.NOT_POWERED);
                }
                ChannelCatalog catalog = ChannelCatalog.get();
                if (catalog == null || catalog.isEmpty()) {
                    return Outcome.reject(RejectReason.NO_CHANNELS);
                }
                Optional<ChannelDefinition> target;
                if (type == TvActionType.SELECT_CHANNEL) {
                    target = catalog.byNumber(args.intArg());
                    if (target.isEmpty()) {
                        return Outcome.reject(RejectReason.CHANNEL_MISSING);
                    }
                    if (!target.get().enabled()) {
                        return Outcome.reject(RejectReason.CHANNEL_DISABLED);
                    }
                } else {
                    target = catalog.step(s.channelNumber(), args.intArg() >= 0 ? 1 : -1);
                    if (target.isEmpty()) {
                        return Outcome.reject(RejectReason.NO_CHANNELS);
                    }
                }
                return tune(svc, session, be, target.get());
            }
            case QUEUE_NEXT -> {
                return advanceQueue(svc, session, be) ? Outcome.OK : Outcome.reject(RejectReason.QUEUE_EMPTY);
            }
            case SET_SOURCE -> {
                MediaSource source = args.source();
                if (source == null) {
                    return Outcome.reject(RejectReason.INVALID_ARGUMENT);
                }
                ServerSourcePolicy.Verdict verdict = ServerSourcePolicy.check(source);
                if (!verdict.allowed()) {
                    return Outcome.reject(verdict.reason(), verdict.detail());
                }
                return startSource(svc, session, be, source, "", 0, TvDelta.CHANNEL);
            }
            case CLEAR_SOURCE -> {
                s.setSource(null);
                s.setChannel("", 0);
                svc.newGeneration(session);
                session.timeline = TimelineAnchor.STOPPED;
                s.setCursor(0, false);
                return commit(svc, session, be, TvDelta.SOURCE | TvDelta.CHANNEL | TvDelta.TIMELINE, true);
            }
            case QUEUE_ADD -> {
                MediaSource source = args.source();
                if (source == null) {
                    return Outcome.reject(RejectReason.INVALID_ARGUMENT);
                }
                ServerSourcePolicy.Verdict verdict = ServerSourcePolicy.check(source);
                if (!verdict.allowed()) {
                    return Outcome.reject(verdict.reason(), verdict.detail());
                }
                int max = Math.min(Limits.MAX_QUEUE, OteTvServerConfig.MAX_QUEUE_ENTRIES.get());
                if (s.queue().size() >= max) {
                    return Outcome.reject(RejectReason.QUEUE_FULL);
                }
                s.queue().add(source);
                if (s.source() == null && s.powered()) {
                    s.setQueueIndex(s.queue().size() - 1);
                    return startSource(svc, session, be, source, "", 0, TvDelta.QUEUE | TvDelta.CHANNEL);
                }
                return commit(svc, session, be, TvDelta.QUEUE, true);
            }
            case QUEUE_REMOVE -> {
                int index = args.intArg();
                if (index < 0 || index >= s.queue().size()) {
                    return Outcome.reject(RejectReason.INVALID_ARGUMENT);
                }
                s.queue().remove(index);
                if (s.queueIndex() >= index) {
                    s.setQueueIndex(Math.max(-1, s.queueIndex() - 1));
                }
                return commit(svc, session, be, TvDelta.QUEUE, true);
            }
            case QUEUE_CLEAR -> {
                s.queue().clear();
                s.setQueueIndex(-1);
                return commit(svc, session, be, TvDelta.QUEUE, true);
            }
            case SET_DISPLAY_NAME -> {
                s.setDisplayName(TextSanitizer.sanitize(args.stringArg(), Limits.MAX_DISPLAY_NAME));
                return commit(svc, session, be, TvDelta.NAME, true);
            }
            case SET_VISUAL_PRESET -> {
                s.setPreset(VisualPreset.byOrdinal(args.intArg()));
                return commit(svc, session, be, TvDelta.PRESET, true);
            }
            case SET_REDSTONE_MODE -> {
                s.setRedstoneMode(RedstoneMode.byOrdinal(args.intArg()));
                return commit(svc, session, be, TvDelta.REDSTONE, true);
            }
            case SET_PUBLIC_CONTROLS -> {
                s.setPublicControls(args.intArg() != 0);
                return commitAcl(svc, session, be);
            }
            case TRUST_SET -> {
                TvRole newRole = args.intArg() >= 0 && args.intArg() < TvRole.values().length ? TvRole.values()[args.intArg()] : null;
                if (newRole == null || !newRole.isAssignable()) {
                    return Outcome.reject(RejectReason.INVALID_ARGUMENT);
                }
                Optional<GameProfile> profile = resolveProfile(svc, args.stringArg(), args.uuidArg());
                if (profile.isEmpty()) {
                    return Outcome.reject(RejectReason.PLAYER_UNKNOWN, args.stringArg());
                }
                UUID id = profile.get().getId();
                if (id.equals(s.owner())) {
                    return Outcome.reject(RejectReason.INVALID_ARGUMENT, "owner");
                }
                if (!s.trusted().containsKey(id) && s.trusted().size() >= Limits.MAX_TRUSTED) {
                    return Outcome.reject(RejectReason.LIMIT_REACHED);
                }
                s.trust(id, TextSanitizer.sanitize(profile.get().getName(), Limits.MAX_PLAYER_NAME), newRole);
                return commitAcl(svc, session, be);
            }
            case TRUST_REMOVE -> {
                UUID id = args.uuidArg();
                if (id == null) {
                    id = resolveProfile(svc, args.stringArg(), null).map(GameProfile::getId).orElse(null);
                }
                if (id == null || !s.trusted().containsKey(id)) {
                    return Outcome.reject(RejectReason.PLAYER_UNKNOWN, args.stringArg());
                }
                s.untrust(id);
                return commitAcl(svc, session, be);
            }
            case RESET -> {
                s.resetPlayback(be.model().defaultPreset());
                svc.newGeneration(session);
                session.timeline = TimelineAnchor.STOPPED;
                session.lease = null;
                svc.bump(session);
                be.markDurableChange(true);
                svc.sendSnapshots(session, be);
                be.syncPublicState();
                return Outcome.OK;
            }
            default -> {
                return Outcome.reject(RejectReason.INVALID_ARGUMENT);
            }
        }
    }

    private static long initialPosition(@Nullable MediaSource source) {
        if (source != null && source.youtube().isPresent()) {
            return source.youtube().get().startSeconds() * 1000L;
        }
        return 0;
    }

    private static Outcome tune(TvSessionService svc, TvSession session, TvBlockEntity be, ChannelDefinition channel) {
        ServerSourcePolicy.Verdict verdict = ServerSourcePolicy.check(channel.source());
        if (!verdict.allowed()) {
            return Outcome.reject(verdict.reason(), verdict.detail());
        }
        return startSource(svc, session, be, channel.source(), channel.id(), channel.number(), TvDelta.CHANNEL);
    }

    /** Sets a new shared source: a new source generation, a fresh scheduled timeline, and power on. */
    static Outcome startSource(TvSessionService svc, TvSession session, TvBlockEntity be, MediaSource source, String channelId,
                               int channelNumber, int extraMask) {
        TvState s = be.state();
        long now = svc.now();
        boolean wasPowered = s.powered();
        s.setPowered(true);
        s.setSource(source);
        s.setChannel(channelId, channelNumber);
        s.setDurationHint(source.declaredDurationMs());
        svc.newGeneration(session);
        // Liveness is learned from trusted viewer reports once a client has parsed the playlist.
        boolean live = false;
        long start = initialPosition(source);
        boolean canPlay = svc.activeSessionsIn(session.dimension) < OteTvServerConfig.MAX_ACTIVE_SESSIONS_PER_DIMENSION.get()
                || session.timeline.state() == PlayState.PLAYING;
        session.timeline = new TimelineAnchor(canPlay ? PlayState.PLAYING : PlayState.PAUSED, start, now + lead(source), 1.0,
                source.declaredDurationMs(), live);
        s.setCursor(start, canPlay);
        int mask = TvDelta.SOURCE | TvDelta.TIMELINE | extraMask | (wasPowered ? 0 : TvDelta.POWER);
        return commit(svc, session, be, mask, true);
    }

    /** Advances the TV's queue; returns false when there is no next entry. */
    public static boolean advanceQueue(TvSessionService svc, TvSession session, TvBlockEntity be) {
        TvState s = be.state();
        int next = s.queueIndex() + 1;
        if (next < 0 || next >= s.queue().size()) {
            return false;
        }
        MediaSource source = s.queue().get(next);
        if (!ServerSourcePolicy.check(source).allowed()) {
            return false;
        }
        s.setQueueIndex(next);
        startSource(svc, session, be, source, "", 0, TvDelta.QUEUE | TvDelta.CHANNEL);
        return true;
    }

    /** Redstone changes shared desired state only, debounced, with at most one channel change per second. */
    public static void redstone(TvSessionService svc, TvSession session, TvBlockEntity be, boolean high) {
        TvState s = be.state();
        boolean rising = high && !s.lastRedstoneHigh();
        if (s.lastRedstoneHigh() != high) {
            s.setLastRedstoneHigh(high);
            be.markDurableChange(false);
        }
        if (!OteTvServerConfig.ALLOW_REDSTONE_CONTROL.get() || s.isLocked()) {
            return;
        }
        switch (s.redstoneMode()) {
            case POWER_WHEN_HIGH -> {
                if (s.powered() != high) {
                    perform(svc, session, be, null, TvActionType.SET_POWER, -1, Args.ofInt(high ? 1 : 0));
                }
            }
            case TOGGLE_POWER_ON_RISING_EDGE -> {
                if (rising) {
                    perform(svc, session, be, null, TvActionType.SET_POWER, -1, Args.ofInt(s.powered() ? 0 : 1));
                }
            }
            case NEXT_CHANNEL_ON_RISING_EDGE -> {
                long now = svc.now();
                if (rising && now - session.lastRedstoneChannelStep >= 1_000_000_000L) {
                    session.lastRedstoneChannelStep = now;
                    perform(svc, session, be, null, TvActionType.CHANNEL_STEP, -1, Args.ofInt(1));
                }
            }
            default -> {
            }
        }
    }

    /** A catalog reload removed the TV's channel: fall back to standby, never an unrelated number (section 18.3). */
    public static void onChannelRemoved(TvSessionService svc, TvSession session, TvBlockEntity be) {
        TvState s = be.state();
        s.setSource(null);
        s.setChannel("", 0);
        svc.newGeneration(session);
        session.timeline = TimelineAnchor.STOPPED;
        s.setCursor(0, false);
        commit(svc, session, be, TvDelta.SOURCE | TvDelta.CHANNEL | TvDelta.TIMELINE, true);
    }

    private static Outcome commit(TvSessionService svc, TvSession session, TvBlockEntity be, int mask, boolean durable) {
        svc.bump(session);
        if (durable) {
            be.markDurableChange(true);
        }
        svc.broadcastDelta(session, be, mask, true);
        return Outcome.OK;
    }

    private static Outcome commitAcl(TvSessionService svc, TvSession session, TvBlockEntity be) {
        svc.bump(session);
        be.markDurableChange(true);
        svc.sendSnapshots(session, be);
        be.syncPublicState();
        return Outcome.OK;
    }

    private static Optional<GameProfile> resolveProfile(TvSessionService svc, String name, @Nullable UUID id) {
        if (id != null) {
            ServerPlayer online = svc.server().getPlayerList().getPlayer(id);
            if (online != null) {
                return Optional.of(online.getGameProfile());
            }
            if (svc.server().getProfileCache() != null) {
                return svc.server().getProfileCache().get(id);
            }
            return Optional.empty();
        }
        if (name == null || name.isEmpty() || name.length() > Limits.MAX_PLAYER_NAME) {
            return Optional.empty();
        }
        ServerPlayer online = svc.server().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        return svc.server().getProfileCache() == null ? Optional.empty() : svc.server().getProfileCache().get(name);
    }
}
