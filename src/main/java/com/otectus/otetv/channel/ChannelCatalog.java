package com.otectus.otetv.channel;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.json.ChannelJson;
import com.otectus.otetv.data.OteTvWorldData;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.session.TvActions;
import com.otectus.otetv.session.TvSession;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The merged channel catalog: datapack definitions plus operator-defined channels from world data (section 18.3).
 * Duplicate numbers and ids fail individually with diagnostics; the catalog is capped; every rebuild increments the
 * revision. TVs tuned to a channel that disappeared fall back to standby.
 */
public final class ChannelCatalog {
    @Nullable
    private static volatile ChannelCatalog current;
    private static List<ChannelDefinition> datapackChannels = List.of();
    private static long revisionCounter;

    private final List<ChannelDefinition> channels;
    private final long revision;
    private final List<String> diagnostics;

    private ChannelCatalog(List<ChannelDefinition> channels, long revision, List<String> diagnostics) {
        this.channels = channels;
        this.revision = revision;
        this.diagnostics = diagnostics;
    }

    @Nullable
    public static ChannelCatalog get() {
        return current;
    }

    /** Called by the reload listener with freshly validated datapack definitions. */
    static void setDatapackChannels(List<ChannelDefinition> defs, MinecraftServer serverOrNull) {
        datapackChannels = List.copyOf(defs);
        if (serverOrNull != null) {
            rebuild(serverOrNull);
        }
    }

    public static void rebuild(MinecraftServer server) {
        List<ChannelDefinition> merged = new ArrayList<>(datapackChannels);
        merged.addAll(OteTvWorldData.get(server).adminChannels());
        merged.addAll(com.otectus.otetv.api.OteTvApi.programmaticChannels());
        List<String> diagnostics = new ArrayList<>(ChannelJson.findDuplicates(merged));
        List<ChannelDefinition> accepted = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        int cap = Math.min(Limits.MAX_CHANNELS, OteTvServerConfig.MAX_CHANNELS.get());
        for (ChannelDefinition def : merged) {
            if (!ids.add(def.id()) || !numbers.add(def.number())) {
                continue; // first occurrence wins; the duplicate was reported above
            }
            if (accepted.size() >= cap) {
                diagnostics.add("catalog cap " + cap + " reached; dropped " + def.id());
                continue;
            }
            accepted.add(def);
        }
        accepted.sort(Comparator.comparingInt(ChannelDefinition::number));
        ChannelCatalog catalog = new ChannelCatalog(Collections.unmodifiableList(accepted), ++revisionCounter, List.copyOf(diagnostics));
        current = catalog;
        for (String d : diagnostics) {
            OteTV.LOGGER.warn("Channel catalog: {}", d);
        }
        OteTV.LOGGER.info("Channel catalog revision {} has {} channel(s)", catalog.revision, accepted.size());
        fallBackMissingChannels(server, catalog);
    }

    private static void fallBackMissingChannels(MinecraftServer server, ChannelCatalog catalog) {
        TvSessionService svc = TvSessionService.get();
        if (svc == null) {
            return;
        }
        for (TvSession session : new ArrayList<TvSession>(collect(svc))) {
            TvBlockEntity be = svc.blockEntity(session);
            if (be == null) {
                continue;
            }
            String id = be.state().channelId();
            if (!id.isEmpty() && catalog.byId(id).isEmpty()) {
                TvActions.onChannelRemoved(svc, session, be);
            }
        }
    }

    private static List<TvSession> collect(TvSessionService svc) {
        List<TvSession> list = new ArrayList<>();
        svc.sessions().forEach(list::add);
        return list;
    }

    public static void clear() {
        current = null;
    }

    public long revision() {
        return revision;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public List<ChannelDefinition> channels() {
        return channels;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public Optional<ChannelDefinition> byNumber(int number) {
        return channels.stream().filter(c -> c.number() == number).findFirst();
    }

    public Optional<ChannelDefinition> byId(String id) {
        return channels.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Next or previous enabled channel, wrapping around; disabled entries are skipped. */
    public Optional<ChannelDefinition> step(int fromNumber, int direction) {
        List<ChannelDefinition> enabled = channels.stream().filter(ChannelDefinition::enabled).toList();
        if (enabled.isEmpty()) {
            return Optional.empty();
        }
        if (direction > 0) {
            for (ChannelDefinition c : enabled) {
                if (c.number() > fromNumber) {
                    return Optional.of(c);
                }
            }
            return Optional.of(enabled.get(0));
        }
        for (int i = enabled.size() - 1; i >= 0; i--) {
            if (enabled.get(i).number() < fromNumber) {
                return Optional.of(enabled.get(i));
            }
        }
        return Optional.of(enabled.get(enabled.size() - 1));
    }

    /**
     * The raw label: a literal name, or a translation key that clients resolve with their own language (servers do
     * not have mod language files).
     */
    public static String label(ChannelDefinition def) {
        return def.displayName();
    }

    public S2C.ChannelCatalog page(int page) {
        int pageSize = Limits.CATALOG_PAGE_SIZE;
        int pages = Math.max(1, (channels.size() + pageSize - 1) / pageSize);
        int p = Math.max(0, Math.min(page, pages - 1));
        List<S2C.ChannelEntry> entries = new ArrayList<>();
        for (int i = p * pageSize; i < Math.min(channels.size(), (p + 1) * pageSize); i++) {
            ChannelDefinition c = channels.get(i);
            entries.add(new S2C.ChannelEntry(c.number(), c.id(), c.displayName(), c.translatable(), c.description(), c.source(), c.enabled()));
        }
        return new S2C.ChannelCatalog(revision, p, pages, entries);
    }
}
