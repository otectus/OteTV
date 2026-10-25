package com.otectus.otetv.channel;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.json.ChannelJson;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.session.ServerSourcePolicy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads {@code data/<namespace>/otetv/channels/*.json}. Datapacks may define sources but can never grant network
 * consent (section 16.1). Bad entries fail individually with a useful log line instead of half-parsing.
 */
public final class ChannelReloadListener extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private final Supplier<MinecraftServer> server;

    public ChannelReloadListener(Supplier<MinecraftServer> server) {
        super(GSON, "otetv/channels");
        this.server = server;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
        List<ChannelDefinition> defs = new ArrayList<>();
        MediaSource.Policy policy;
        try {
            policy = ServerSourcePolicy.structuralPolicy();
        } catch (IllegalStateException configNotLoaded) {
            policy = MediaSource.Policy.STRUCTURAL;
        }
        for (Map.Entry<ResourceLocation, JsonElement> e : entries.entrySet()) {
            ChannelJson.Result result = ChannelJson.parse(e.getValue(), policy, ChannelDefinition.Origin.DATAPACK);
            if (result instanceof ChannelJson.Ok ok) {
                defs.add(ok.definition());
            } else if (result instanceof ChannelJson.Failed failed) {
                OteTV.LOGGER.warn("Rejected channel definition {}: {}", e.getKey(), failed.error());
            }
        }
        @Nullable MinecraftServer s = server.get();
        ChannelCatalog.setDatapackChannels(defs, s);
    }
}
