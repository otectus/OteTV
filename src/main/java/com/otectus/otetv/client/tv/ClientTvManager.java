package com.otectus.otetv.client.tv;

import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.client.store.ConsentStore;
import com.otectus.otetv.client.store.LocalMediaLibrary;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvDelta;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Client session manager (section 8): known TV states, joined playback sessions, the clock, the channel catalog and
 * pending actions. Enforces the client's active-session ceiling (two, at most one YouTube) by priority: the nearest
 * relevant TV plays, excess sessions show a paused placeholder.
 */
public final class ClientTvManager {
    private static final ClientTvManager INSTANCE = new ClientTvManager();

    private final Map<UUID, ClientTvState> states = new HashMap<>();
    private final Map<UUID, ClientPlaybackSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, ClientPlaybackSession> privateSessions = new HashMap<>();
    private final Map<UUID, S2C.QueuePage> queues = new HashMap<>();
    private final Map<Integer, UUID> pendingActions = new HashMap<>();
    private final ClockSync clock = new ClockSync();
    private final CatalogCache catalog = new CatalogCache();
    private final List<Consumer<S2C.ActionResult>> resultListeners = new ArrayList<>();
    private int nextActionId = 1;
    private boolean trustListenerInstalled;

    private ClientTvManager() {
    }

    public static ClientTvManager get() {
        return INSTANCE;
    }

    public ClockSync clock() {
        return clock;
    }

    public CatalogCache catalog() {
        return catalog;
    }

    private void ensureTrustListener() {
        if (!trustListenerInstalled) {
            trustListenerInstalled = true;
            ConsentStore.addListener(() -> Minecraft.getInstance().execute(this::onTrustChanged));
        }
    }

    // ---- state ------------------------------------------------------------------------------------------------------

    public ClientTvState state(UUID tv) {
        return states.computeIfAbsent(tv, ClientTvState::new);
    }

    @Nullable
    public TvSnapshot snapshot(UUID tv) {
        ClientTvState s = states.get(tv);
        return s == null ? null : s.snapshot();
    }

    @Nullable
    public ClientPlaybackSession session(UUID tv) {
        ClientPlaybackSession p = privateSessions.get(tv);
        return p != null ? p : sessions.get(tv);
    }

    @Nullable
    public ClientPlaybackSession sharedSession(UUID tv) {
        return sessions.get(tv);
    }

    public boolean isJoined(UUID tv) {
        return sessions.containsKey(tv);
    }

    public boolean hasPrivate(UUID tv) {
        return privateSessions.containsKey(tv);
    }

    @Nullable
    public S2C.QueuePage queue(UUID tv) {
        return queues.get(tv);
    }

    public void requestSnapshot(UUID tv) {
        ClientTvState s = state(tv);
        if (s.mayRequest(System.nanoTime())) {
            long known = s.snapshot() == null ? 0 : s.snapshot().revision();
            OteTvNetwork.sendToServer(new C2S.RequestSnapshot(tv, known));
        }
    }

    public void requestQueue(UUID tv) {
        OteTvNetwork.sendToServer(new C2S.RequestQueue(tv));
    }

    public void onSnapshot(TvSnapshot s) {
        clock.noteEpoch(s.epoch());
        ClientTvState state = state(s.tvId());
        state.accept(s);
        ClientPlaybackSession session = sessions.get(s.tvId());
        if (session != null) {
            placeSession(session);
            session.onShared(state.snapshot());
        }
    }

    public void onDelta(TvDelta d) {
        ClientTvState state = state(d.tvId());
        if (!state.apply(d)) {
            requestSnapshot(d.tvId());
            return;
        }
        ClientPlaybackSession session = sessions.get(d.tvId());
        if (session != null && state.snapshot() != null) {
            session.onShared(state.snapshot());
        }
    }

    public void onTiming(S2C.Timing t) {
        ClientTvState state = state(t.tv());
        if (!state.applyTiming(t)) {
            requestSnapshot(t.tv());
            return;
        }
        ClientPlaybackSession session = sessions.get(t.tv());
        if (session != null && state.snapshot() != null) {
            session.onShared(state.snapshot());
        }
    }

    public void onSessionRemoved(S2C.SessionRemoved r) {
        ClientTvState state = states.get(r.tv());
        if (state != null) {
            state.markRemoved();
        }
        closeSession(r.tv());
        if (r.destroyed()) {
            states.remove(r.tv());
            queues.remove(r.tv());
        }
    }

    public void onCatalog(S2C.ChannelCatalog page) {
        catalog.accept(page);
    }

    public void onQueuePage(S2C.QueuePage page) {
        queues.put(page.tv(), page);
    }

    public void onClockReply(S2C.ClockReply reply, long c3) {
        clock.onReply(reply, c3);
    }

    // ---- actions ------------------------------------------------------------------------------------------------------

    public void addResultListener(Consumer<S2C.ActionResult> listener) {
        resultListeners.add(listener);
    }

    public void removeResultListener(Consumer<S2C.ActionResult> listener) {
        resultListeners.remove(listener);
    }

    public int sendAction(UUID tv, TvActionType type, long longArg, int intArg, String stringArg, @Nullable MediaSource source, @Nullable UUID uuidArg) {
        TvSnapshot s = snapshot(tv);
        long revision = s == null ? 0 : s.revision();
        int id = nextActionId++;
        if (nextActionId > 1_000_000) {
            nextActionId = 1;
        }
        pendingActions.put(id, tv);
        OteTvNetwork.sendToServer(new C2S.TvAction(tv, revision, id, type, longArg, intArg, stringArg == null ? "" : stringArg, source, uuidArg));
        return id;
    }

    public int sendAction(UUID tv, TvActionType type) {
        return sendAction(tv, type, 0, 0, "", null, null);
    }

    public void onActionResult(S2C.ActionResult r) {
        pendingActions.remove(r.actionId());
        // actionId -1 answers a snapshot request ("TV unavailable"); screens handle it through their listener.
        if (!r.accepted() && r.reason() != RejectReason.NONE && r.actionId() != -1) {
            Component message = Component.translatable(r.reason().translationKey());
            if (!r.detail().isEmpty() && r.reason() == RejectReason.LEASE_HELD) {
                message = Component.translatable("otetv.reject.lease_held_by", r.detail());
            }
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(message, true);
            }
        }
        for (Consumer<S2C.ActionResult> l : List.copyOf(resultListeners)) {
            l.accept(r);
        }
    }

    // ---- joining ------------------------------------------------------------------------------------------------------

    /** Joins a TV's shared session. Joining is local and explicit; seeing a TV never fetches media (section 7.1). */
    public void join(UUID tv) {
        ensureTrustListener();
        if (sessions.containsKey(tv)) {
            return;
        }
        ClientPlaybackSession session = new ClientPlaybackSession(tv, ClientPlaybackSession.Mode.SHARED);
        sessions.put(tv, session);
        placeSession(session);
        int caps = (com.otectus.otetv.client.media.RuntimeManager.mediaInfo().status() == com.otectus.otetv.client.media.RuntimeManager.MediaStatus.AVAILABLE
                ? C2S.JoinViewing.DIRECT_RUNTIME : 0)
                | (com.otectus.otetv.client.media.RuntimeManager.browserInfo().status() == com.otectus.otetv.client.media.RuntimeManager.BrowserStatus.INSTALLED
                ? C2S.JoinViewing.BROWSER_RUNTIME : 0);
        OteTvNetwork.sendToServer(new C2S.JoinViewing(tv, caps));
        rebalance();
        TvSnapshot s = snapshot(tv);
        if (s != null) {
            session.onShared(s);
        }
    }

    public void leave(UUID tv) {
        if (sessions.containsKey(tv)) {
            OteTvNetwork.sendToServer(new C2S.LeaveViewing(tv));
            closeSession(tv);
        }
    }

    public void startPrivate(UUID tv, LocalMediaLibrary.Entry entry) {
        ensureTrustListener();
        stopPrivate(tv);
        ClientPlaybackSession session = new ClientPlaybackSession(tv, ClientPlaybackSession.Mode.PRIVATE);
        privateSessions.put(tv, session);
        placeSession(session);
        try {
            session.startPrivate(entry);
        } catch (Exception e) {
            privateSessions.remove(tv);
            session.close();
        }
        rebalance();
    }

    public void stopPrivate(UUID tv) {
        ClientPlaybackSession p = privateSessions.remove(tv);
        if (p != null) {
            p.close();
        }
    }

    private void closeSession(UUID tv) {
        ClientPlaybackSession s = sessions.remove(tv);
        if (s != null) {
            s.close();
        }
        stopPrivate(tv);
        rebalance();
    }

    /** Finds the TV's block entity in the current level and records its placement on the session. */
    private void placeSession(ClientPlaybackSession session) {
        findBlockEntity(session.tvId()).ifPresent(be ->
                session.setPlacement(be.getBlockPos(), be.facing(), be.model(), be.mount()));
        TvSnapshot s = snapshot(session.tvId());
        if (s != null && findBlockEntity(session.tvId()).isEmpty()) {
            TvModel model = TvModel.byName(s.model());
            session.setPlacement(s.pos(), net.minecraft.core.Direction.NORTH, model == null ? TvModel.CRT : model, TvMount.STAND);
        }
    }

    public Optional<TvBlockEntity> findBlockEntity(UUID tv) {
        TvSnapshot s = snapshot(tv);
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return Optional.empty();
        }
        if (s != null && level.isLoaded(s.pos())) {
            BlockEntity be = level.getBlockEntity(s.pos());
            if (be instanceof TvBlockEntity tvBe && tv.equals(tvBe.tvId())) {
                return Optional.of(tvBe);
            }
        }
        return Optional.empty();
    }

    @Nullable
    public UUID tvAt(BlockPos anchor) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.isLoaded(anchor)) {
            return null;
        }
        return level.getBlockEntity(anchor) instanceof TvBlockEntity be ? be.tvId() : null;
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------------

    public void onTelevisionLoaded(TvBlockEntity be) {
        UUID id = be.tvId();
        if (id != null && OteTvClientConfig.AUTO_JOIN_SHARED_TVS.get() && be.publicState().powered() && be.publicState().hasSource()) {
            requestSnapshot(id);
        }
    }

    /** Chunk unload or destruction: audio stops and resources release (section 21.3). */
    public void onTelevisionGone(TvBlockEntity be, boolean destroyed) {
        UUID id = be.tvId();
        if (id == null) {
            return;
        }
        if (sessions.containsKey(id)) {
            leave(id);
        }
        stopPrivate(id);
        if (destroyed) {
            states.remove(id);
        }
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean active = !sessions.isEmpty();
        clock.tick(active);
        int range = OteTvClientConfig.MAX_AUDIO_RANGE.get() * 3 + 16;
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            ClientPlaybackSession s = sessions.get(id);
            TvSnapshot snap = snapshot(id);
            boolean far = snap != null && (!snap.dimension().equals(mc.level.dimension().location().toString())
                    || mc.player.position().distanceTo(s.audioPosition()) > Math.max(range, 64));
            if (far) {
                leave(id);
                continue;
            }
            s.tick();
            if (snap != null && snap.locked()) {
                leave(id);
            }
        }
        for (ClientPlaybackSession p : new ArrayList<>(privateSessions.values())) {
            p.tick();
        }
        if (mc.level.getGameTime() % 20 == 0) {
            rebalance();
            for (UUID id : sessions.keySet()) {
                TvSnapshot s = snapshot(id);
                if (s == null || states.get(id).ageMs() > 30_000) {
                    requestSnapshot(id);
                }
            }
        }
    }

    /** Enforces the active-session ceiling by distance priority. */
    private void rebalance() {
        var player = Minecraft.getInstance().player;
        List<ClientPlaybackSession> all = new ArrayList<>(sessions.values());
        all.addAll(privateSessions.values());
        if (player != null) {
            all.sort(Comparator.comparingDouble(s -> s.audioPosition().distanceToSqr(player.position())));
        }
        int max = OteTvClientConfig.MAX_ACTIVE_PLAYERS.get();
        int running = 0;
        boolean youtube = false;
        for (ClientPlaybackSession s : all) {
            // A shared session hidden behind a private one on the same TV never runs.
            if (s.mode() == ClientPlaybackSession.Mode.SHARED && privateSessions.containsKey(s.tvId())) {
                s.setAllowedToRun(false);
                continue;
            }
            boolean isYouTube = s.source() != null && s.source().type() == SourceType.YOUTUBE;
            boolean allow = running < max && !(isYouTube && youtube);
            s.setAllowedToRun(allow);
            if (allow) {
                running++;
                youtube |= isYouTube;
            }
        }
    }

    private void onTrustChanged() {
        for (ClientPlaybackSession s : new ArrayList<>(sessions.values())) {
            if (s.source() != null && s.source().type().requiresNetwork()) {
                s.restart(); // waiting sessions start; revoked ones stop immediately
            }
        }
    }

    /** Disconnect or world unload: every session, worker and texture is released. */
    public void reset() {
        for (ClientPlaybackSession s : sessions.values()) {
            s.close();
        }
        for (ClientPlaybackSession s : privateSessions.values()) {
            s.close();
        }
        sessions.clear();
        privateSessions.clear();
        states.clear();
        queues.clear();
        pendingActions.clear();
        catalog.clear();
        clock.reset();
    }

    public List<ClientPlaybackSession> allSessions() {
        List<ClientPlaybackSession> all = new ArrayList<>(sessions.values());
        all.addAll(privateSessions.values());
        return all;
    }
}
