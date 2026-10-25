package com.otectus.otetv.api;

import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small extension API (section 20.3): register source adapter descriptions, add approved channel descriptors,
 * query a TV's public server state, request permission-checked changes and observe {@link TvLifecycleEvent}s.
 * Private account and browser objects stay internal; there is no "bypass validation" flag anywhere.
 */
public final class OteTvApi {
    /**
     * A declared adapter: which shareable source type it serves, what it can do, and the trust it requires from each
     * player before any request. This release ships the built-in adapters only; registrations are surfaced in
     * diagnostics and reserved for future qualified adapters.
     */
    public record SourceAdapter(String id, SourceType type, PlaybackCapabilities capabilities, String trustRequirement) {
    }

    /** Read-only public state of a loaded TV. */
    public record TvStateView(UUID tvId, String model, String displayName, boolean powered, int channelNumber,
                              Optional<MediaSource> source, PlayState playState, long positionMs, int viewers) {
    }

    private static final List<SourceAdapter> ADAPTERS = new CopyOnWriteArrayList<>();
    private static final List<ChannelDefinition> PROGRAMMATIC_CHANNELS = new CopyOnWriteArrayList<>();

    private OteTvApi() {
    }

    public static void registerSourceAdapter(SourceAdapter adapter) {
        if (!adapter.type().isShareable()) {
            throw new IllegalArgumentException("adapters must serve a shareable source type");
        }
        ADAPTERS.add(adapter);
    }

    public static List<SourceAdapter> sourceAdapters() {
        return Collections.unmodifiableList(new ArrayList<>(ADAPTERS));
    }

    /**
     * Adds an approved channel. Its source is validated like datapack entries and it is merged into the catalog on
     * the next rebuild; numbering conflicts are resolved first-come with a diagnostic.
     */
    public static void addChannel(ChannelDefinition definition) {
        PROGRAMMATIC_CHANNELS.add(definition);
    }

    public static List<ChannelDefinition> programmaticChannels() {
        return List.copyOf(PROGRAMMATIC_CHANNELS);
    }

    public static Optional<TvStateView> query(UUID tvId) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null) {
            return Optional.empty();
        }
        TvSession session = svc.session(tvId);
        TvBlockEntity be = session == null ? null : svc.blockEntity(session);
        if (be == null) {
            return Optional.empty();
        }
        TvState s = be.state();
        return Optional.of(new TvStateView(tvId, be.model().registryName(), s.displayName(), s.powered(), s.channelNumber(),
                Optional.ofNullable(s.source()), session.timeline().state(), session.timeline().targetPositionMs(svc.now()),
                session.subscribers().size()));
    }

    /**
     * Requests a change on behalf of a player. Exactly the same permission, lease, revision and source-policy checks
     * apply as for that player's own packets.
     */
    public static RejectReason request(ServerPlayer actor, UUID tvId, TvActionType type, TvActions.Args args) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null) {
            return RejectReason.NOT_LOADED;
        }
        TvSession session = svc.session(tvId);
        TvBlockEntity be = session == null ? null : svc.blockEntity(session);
        if (be == null) {
            return RejectReason.NOT_LOADED;
        }
        return TvActions.perform(svc, session, be, actor, type, -1, args).reason();
    }
}
