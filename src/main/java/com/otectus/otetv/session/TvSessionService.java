package com.otectus.otetv.session;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvAssembly;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.channel.ChannelCatalog;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.ratelimit.TokenBucket;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.core.sync.TimelineAnchor;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvDelta;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server authority for logical TV sessions (section 8.1 TvSessionService). Uses a random epoch per server run and
 * monotonic nanoseconds since that epoch, never Minecraft time-of-day or tick counts (section 10.1). Idle TVs cost
 * nothing per tick; periodic work runs on bounded intervals for active sessions only.
 */
public final class TvSessionService {
    private static final long SECOND = 1_000_000_000L;
    private static final long TIMING_INTERVAL = Limits.TIMING_BROADCAST_INTERVAL_MS * 1_000_000L;
    private static final long CHECKPOINT_INTERVAL = 30 * SECOND;
    private static final long INTEGRITY_INTERVAL = 10 * SECOND;
    @Nullable
    private static volatile TvSessionService instance;

    private final MinecraftServer server;
    private final long epoch;
    private final long epochStart;
    private final Map<UUID, TvSession> sessions = new HashMap<>();
    private final Map<UUID, TokenBucket> actionBuckets = new HashMap<>();
    private final Map<UUID, Long> lastClockProbe = new HashMap<>();
    private int tickCounter;

    private TvSessionService(MinecraftServer server) {
        this.server = server;
        this.epoch = new SecureRandom().nextLong() & Long.MAX_VALUE;
        this.epochStart = System.nanoTime();
    }

    public static void start(MinecraftServer server) {
        instance = new TvSessionService(server);
        OteTV.LOGGER.info("OteTV session service started (epoch {})", Long.toHexString(instance.epoch));
    }

    public static void stop() {
        TvSessionService current = instance;
        if (current != null) {
            current.checkpointAll();
        }
        instance = null;
    }

    @Nullable
    public static TvSessionService get() {
        return instance;
    }

    public MinecraftServer server() {
        return server;
    }

    public long epoch() {
        return epoch;
    }

    /** Monotonic nanoseconds since this server run's epoch began. */
    public long now() {
        return System.nanoTime() - epochStart;
    }

    @Nullable
    public TvSession session(UUID tv) {
        return sessions.get(tv);
    }

    public Iterable<TvSession> sessions() {
        return sessions.values();
    }

    @Nullable
    public TvBlockEntity blockEntity(TvSession session) {
        ServerLevel level = server.getLevel(session.dimension);
        if (level == null || !level.isLoaded(session.pos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(session.pos);
        return be instanceof TvBlockEntity tv && tv.tvId() != null && tv.tvId().equals(session.tvId) ? tv : null;
    }

    // ---- controller lifecycle -----------------------------------------------------------------------------------

    public void onControllerLoaded(TvBlockEntity be) {
        ServerLevel level = (ServerLevel) be.getLevel();
        if (level == null) {
            return;
        }
        UUID id = be.tvId();
        if (id == null) {
            return; // not yet initialized by placement; initializePlacement registers it
        }
        TvSession existing = sessions.get(id);
        if (existing != null && (!existing.pos.equals(be.getBlockPos()) || existing.dimension != level.dimension())) {
            TvBlockEntity other = blockEntity(existing);
            if (other != null && other != be) {
                // Two controllers share an identity (copied by structure tools): the newcomer gets a fresh one.
                be.regenerateIdentity();
                id = be.tvId();
                OteTV.LOGGER.warn("Duplicate television identity at {}; assigned a fresh identity", be.getBlockPos());
            }
        }
        TvSession session = new TvSession(id, level.dimension(), be.getBlockPos(), be.model());
        TvState state = be.state();
        long duration = state.durationHintMs();
        boolean live = state.source() != null && state.source().type() == SourceType.HLS_CLEAR && duration < 0;
        // Restart paused at the saved cursor (section 9.2); live sources resume only after viewers join and press play.
        PlayState restored = state.source() == null ? PlayState.STOPPED : PlayState.PAUSED;
        session.timeline = new TimelineAnchor(restored, state.cursorMs(), now(), 1.0, duration, false).withDuration(duration, live);
        session.lastCheckpoint = now();
        session.lastIntegrityCheck = now();
        sessions.put(id, session);
        OteTvWorldData.get(server).track(id, level.dimension(), be.getBlockPos(), state.owner(), be.model());
    }

    /** Chunk unload: checkpoint and suspend; never force-load (section 10.5). */
    public void onControllerUnloaded(TvBlockEntity be) {
        UUID id = be.tvId();
        if (id == null) {
            return;
        }
        TvSession session = sessions.get(id);
        if (session == null || !session.pos.equals(be.getBlockPos())) {
            return;
        }
        checkpoint(session, be);
        broadcastRemoved(session, false);
        sessions.remove(id);
    }

    /** The TV was broken or replaced: old remotes and sessions must never control a new TV at the same spot. */
    public void onControllerRemoved(TvBlockEntity be) {
        UUID id = be.tvId();
        if (id == null) {
            return;
        }
        TvSession session = sessions.get(id);
        if (session != null && session.pos.equals(be.getBlockPos())) {
            broadcastRemoved(session, true);
            sessions.remove(id);
        }
        OteTvWorldData.get(server).untrack(id);
    }

    private void broadcastRemoved(TvSession session, boolean destroyed) {
        S2C.SessionRemoved msg = new S2C.SessionRemoved(session.tvId, epoch, destroyed);
        for (ServerPlayer p : onlineSubscribers(session)) {
            OteTvNetwork.sendTo(p, msg);
        }
        session.subscribers.clear();
    }

    // ---- per-tick work -------------------------------------------------------------------------------------------

    public void tick() {
        if (++tickCounter % 20 != 0) {
            return;
        }
        long now = now();
        List<TvSession> snapshot = new ArrayList<>(sessions.values());
        for (TvSession session : snapshot) {
            TvBlockEntity be = blockEntity(session);
            if (be == null) {
                continue;
            }
            maintainSubscribers(session, now);
            expireLease(session, be, now);
            autoPause(session, be, now);
            advanceIfEnded(session, be, now);
            if (session.timeline.state() == PlayState.PLAYING && now - session.lastTimingBroadcast >= TIMING_INTERVAL && !session.subscribers.isEmpty()) {
                session.lastTimingBroadcast = now;
                S2C.Timing timing = new S2C.Timing(session.tvId, epoch, session.revision, session.sourceGeneration, session.timeline);
                OteTvNetwork.sendTo(onlineSubscribers(session), timing);
            }
            if (session.timeline.state() == PlayState.PLAYING && now - session.lastCheckpoint >= CHECKPOINT_INTERVAL) {
                checkpoint(session, be);
            }
            if (now - session.lastIntegrityCheck >= INTEGRITY_INTERVAL) {
                session.lastIntegrityCheck = now;
                ServerLevel level = (ServerLevel) be.getLevel();
                BlockState state = be.getBlockState();
                if (level != null) {
                    TvAssembly.validateAndRepair(level, be.getBlockPos(), state);
                }
            }
        }
    }

    private void maintainSubscribers(TvSession session, long now) {
        double range = OteTvServerConfig.VIEWER_RANGE.get();
        Iterator<UUID> it = session.subscribers.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            UUID id = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || p.level().dimension() != session.dimension || p.position().distanceToSqr(session.center()) > range * range * 1.21) {
                it.remove();
                session.reports.remove(id);
                session.lastReportNanos.remove(id);
                changed = true;
            }
        }
        if (changed) {
            TvBlockEntity be = blockEntity(session);
            if (be != null) {
                broadcastDelta(session, be, TvDelta.VIEWERS, false);
            }
        }
    }

    private void expireLease(TvSession session, TvBlockEntity be, long now) {
        if (session.lease != null && session.lease.isExpired(now)) {
            session.lease = null;
            broadcastDelta(session, be, TvDelta.LEASE, false);
        }
    }

    /** Pauses a shared direct VOD session after the grace period when nobody is watching within range. */
    private void autoPause(TvSession session, TvBlockEntity be, long now) {
        if (!OteTvServerConfig.AUTO_PAUSE_EMPTY_VOD.get() || session.timeline.state() != PlayState.PLAYING || session.timeline.live()) {
            session.emptySince = -1;
            return;
        }
        MediaSource source = be.state().source();
        if (source == null) {
            return;
        }
        boolean anyViewer = false;
        double range = OteTvServerConfig.VIEWER_RANGE.get();
        for (UUID id : session.subscribers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && p.position().distanceToSqr(session.center()) <= range * range) {
                anyViewer = true;
                break;
            }
        }
        if (anyViewer) {
            session.emptySince = -1;
            return;
        }
        if (session.emptySince < 0) {
            session.emptySince = now;
        } else if (now - session.emptySince >= OteTvServerConfig.AUTO_PAUSE_GRACE_SECONDS.get() * SECOND) {
            session.emptySince = -1;
            long position = session.timeline.targetPositionMs(now);
            session.timeline = session.timeline.withState(PlayState.PAUSED, position, now);
            be.state().setCursor(position, false);
            bump(session);
            be.markDurableChange(true);
            broadcastDelta(session, be, TvDelta.TIMELINE, true);
            OteTV.LOGGER.debug("Auto-paused empty television {}", session.tvId);
        }
    }

    private void advanceIfEnded(TvSession session, TvBlockEntity be, long now) {
        TimelineAnchor t = session.timeline;
        if (t.state() != PlayState.PLAYING || t.live() || t.durationMs() <= 0 || !t.reachedEnd(now)) {
            return;
        }
        if (!TvActions.advanceQueue(this, session, be)) {
            session.timeline = t.withState(PlayState.STOPPED, 0, now);
            be.state().setCursor(0, false);
            bump(session);
            be.markDurableChange(true);
            broadcastDelta(session, be, TvDelta.TIMELINE, true);
        }
    }

    public void checkpoint(TvSession session, TvBlockEntity be) {
        long now = now();
        session.lastCheckpoint = now;
        be.state().setCursor(session.timeline.targetPositionMs(now), session.timeline.state() == PlayState.PLAYING);
        be.markDurableChange(false);
    }

    private void checkpointAll() {
        for (TvSession session : new ArrayList<>(sessions.values())) {
            TvBlockEntity be = blockEntity(session);
            if (be != null) {
                checkpoint(session, be);
            }
        }
    }

    // ---- players --------------------------------------------------------------------------------------------------

    public void onPlayerLeft(ServerPlayer player) {
        UUID id = player.getUUID();
        for (TvSession session : sessions.values()) {
            boolean changed = session.subscribers.remove(id);
            session.reports.remove(id);
            session.lastReportNanos.remove(id);
            int mask = changed ? TvDelta.VIEWERS : 0;
            if (session.lease != null && session.lease.heldBy(id)) {
                session.lease = null;
                mask |= TvDelta.LEASE;
            }
            if (mask != 0) {
                TvBlockEntity be = blockEntity(session);
                if (be != null) {
                    broadcastDelta(session, be, mask, false);
                }
            }
        }
        actionBuckets.remove(id);
        lastClockProbe.remove(id);
    }

    public boolean allowAction(ServerPlayer player) {
        TokenBucket bucket = actionBuckets.computeIfAbsent(player.getUUID(), k -> new TokenBucket(Limits.ACTIONS_PER_SECOND, Limits.ACTION_BURST));
        return bucket.tryAcquire(System.nanoTime());
    }

    public boolean allowClockProbe(ServerPlayer player) {
        long nowMs = System.nanoTime() / 1_000_000L;
        Long last = lastClockProbe.get(player.getUUID());
        if (last != null && nowMs - last < Limits.CLOCK_PROBE_MIN_INTERVAL_MS) {
            return false;
        }
        lastClockProbe.put(player.getUUID(), nowMs);
        return true;
    }

    public boolean inRange(ServerPlayer player, TvSession session, double range) {
        return player.level().dimension() == session.dimension && player.position().distanceToSqr(session.center()) <= range * range;
    }

    public List<ServerPlayer> onlineSubscribers(TvSession session) {
        List<ServerPlayer> list = new ArrayList<>(session.subscribers.size());
        for (UUID id : session.subscribers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    // ---- revisions, snapshots and broadcasts -------------------------------------------------------------------

    public void bump(TvSession session) {
        session.revision++;
    }

    public void newGeneration(TvSession session) {
        session.sourceGeneration++;
        session.reports.clear();
    }

    public TvSnapshot snapshotFor(@Nullable ServerPlayer player, TvSession session, TvBlockEntity be) {
        TvState s = be.state();
        long now = now();
        TvRole role = player == null ? TvRole.PUBLIC : TvAccess.role(player, s);
        int permissionBits = player == null ? 0 : TvAccess.permissionBits(TvAccess.permissions(player, s));
        ControlLease lease = session.lease != null && !session.lease.isExpired(now) ? session.lease : null;
        ChannelCatalog catalog = ChannelCatalog.get();
        String channelLabel = "";
        if (catalog != null && !s.channelId().isEmpty()) {
            channelLabel = catalog.byId(s.channelId()).map(ChannelCatalog::label).orElse("");
        }
        return new TvSnapshot(session.tvId, epoch, session.revision, session.sourceGeneration, be.model().registryName(),
                s.displayName(), s.ownerName(), role, permissionBits, s.publicControls(), s.powered(), s.channelNumber(),
                s.channelId(), channelLabel, s.source(), session.timeline, s.speakerGain(), s.preset(), s.redstoneMode(),
                s.queue().size(), s.queueIndex(), lease == null ? "" : lease.holderName(), lease == null ? session.leaseGenerationCounter : lease.generation(),
                lease == null ? 0 : lease.remainingMs(now), lease != null && player != null && lease.heldBy(player.getUUID()),
                session.subscribers.size(), catalog == null ? 0 : catalog.revision(), s.isLocked(),
                OteTvServerConfig.ALLOW_YOUTUBE.get(), session.pos, session.dimension.location().toString());
    }

    public void sendSnapshot(ServerPlayer player, TvSession session, TvBlockEntity be) {
        OteTvNetwork.sendTo(player, new S2C.Snapshot(snapshotFor(player, session, be)));
    }

    /** Sends per-player snapshots to all subscribers (used after ACL changes, where roles differ per player). */
    public void sendSnapshots(TvSession session, TvBlockEntity be) {
        for (ServerPlayer p : onlineSubscribers(session)) {
            sendSnapshot(p, session, be);
        }
    }

    /**
     * Broadcasts an ordered delta to subscribers. {@code bumped} says whether the caller already incremented the
     * revision for this change; otherwise the revision is incremented here.
     */
    public void broadcastDelta(TvSession session, TvBlockEntity be, int mask, boolean bumped) {
        long from = bumped ? session.revision - 1 : session.revision;
        if (!bumped) {
            bump(session);
        }
        TvSnapshot full = snapshotFor(null, session, be);
        TvDelta delta = TvDelta.of(full, from, mask);
        S2C.Delta msg = new S2C.Delta(delta);
        for (ServerPlayer p : onlineSubscribers(session)) {
            OteTvNetwork.sendTo(p, msg);
        }
        be.syncPublicState();
    }

    public int activeSessionsIn(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        int n = 0;
        for (TvSession s : sessions.values()) {
            if (s.dimension == dimension && s.timeline.state() == PlayState.PLAYING) {
                n++;
            }
        }
        return n;
    }

    /** Leases: grant if free or expired, renew for the holder, let owners and operators reclaim. */
    public boolean ensureLease(ServerPlayer player, TvSession session, TvBlockEntity be, TvRole role) {
        long now = now();
        long duration = Limits.LEASE_DURATION_MS * 1_000_000L;
        ControlLease lease = session.lease;
        if (lease != null && !lease.isExpired(now)) {
            if (lease.heldBy(player.getUUID())) {
                session.lease = lease.renewed(now, duration);
                return true;
            }
            if (!TvAccess.canReclaimLease(role)) {
                return false;
            }
        }
        session.lease = new ControlLease(player.getUUID(), player.getGameProfile().getName(), now + duration, ++session.leaseGenerationCounter);
        broadcastDelta(session, be, TvDelta.LEASE, false);
        return true;
    }

    public void releaseLease(ServerPlayer player, TvSession session, TvBlockEntity be, int generation) {
        if (session.lease != null && session.lease.heldBy(player.getUUID()) && session.lease.generation() == generation) {
            session.lease = null;
            broadcastDelta(session, be, TvDelta.LEASE, false);
        }
    }

    public boolean hasPermission(ServerPlayer player, TvBlockEntity be, TvPermission permission) {
        return TvAccess.permissions(player, be.state()).contains(permission);
    }

    /** Finds a loaded TV controller at or containing {@code pos}, for commands. Never loads chunks. */
    @Nullable
    public static TvBlockEntity findController(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        return TvAssembly.controller(level, pos, state).orElse(null);
    }

    public static double distanceToAssembly(ServerPlayer player, TvBlockEntity be) {
        AABB box = be.getRenderBoundingBox();
        Vec3 eye = player.getEyePosition();
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
