package com.otectus.otetv.data;

import com.google.gson.JsonParser;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.json.ChannelJson;
import com.otectus.otetv.core.source.MediaSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned world-level data (section 9.2): the world namespace that scopes remote pairings, the registry of placed
 * TVs (for per-player limits and "TV unavailable" lookups without loading chunks) and operator-defined channels.
 * Dirty marking happens on every durable change.
 */
public final class OteTvWorldData extends SavedData {
    private static final String NAME = "otetv";

    public record TvRecord(ResourceKey<Level> dimension, BlockPos pos, @Nullable UUID owner, String model) {
    }

    private UUID worldNamespace = UUID.randomUUID();
    private final Map<UUID, TvRecord> televisions = new HashMap<>();
    private final List<ChannelDefinition> adminChannels = new ArrayList<>();
    private long adminChannelRevision;
    @Nullable
    private CompoundTag incompatible;

    public static OteTvWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(OteTvWorldData::load, OteTvWorldData::new, NAME);
    }

    public static OteTvWorldData load(CompoundTag tag) {
        OteTvWorldData data = new OteTvWorldData();
        int schema = tag.getInt("schema");
        if (schema > OteTvCore.WORLD_DATA_SCHEMA_VERSION) {
            OteTV.LOGGER.error("OteTV world data was written by a newer version (schema {}); it is preserved unchanged and read-only", schema);
            data.incompatible = tag.copy();
            if (tag.hasUUID("namespace")) {
                data.worldNamespace = tag.getUUID("namespace");
            }
            return data;
        }
        if (tag.hasUUID("namespace")) {
            data.worldNamespace = tag.getUUID("namespace");
        }
        ListTag tvs = tag.getList("televisions", Tag.TAG_COMPOUND);
        for (int i = 0; i < tvs.size(); i++) {
            CompoundTag t = tvs.getCompound(i);
            if (!t.hasUUID("id")) {
                continue;
            }
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
            if (dim == null) {
                continue;
            }
            data.televisions.put(t.getUUID("id"), new TvRecord(ResourceKey.create(Registries.DIMENSION, dim),
                    BlockPos.of(t.getLong("pos")), t.hasUUID("owner") ? t.getUUID("owner") : null, t.getString("model")));
        }
        ListTag channels = tag.getList("adminChannels", Tag.TAG_STRING);
        for (int i = 0; i < channels.size(); i++) {
            try {
                ChannelJson.Result r = ChannelJson.parse(JsonParser.parseString(channels.getString(i)), MediaSource.Policy.STRUCTURAL,
                        ChannelDefinition.Origin.ADMIN);
                if (r instanceof ChannelJson.Ok ok) {
                    data.adminChannels.add(ok.definition());
                } else if (r instanceof ChannelJson.Failed failed) {
                    OteTV.LOGGER.warn("Dropped invalid admin channel: {}", failed.error());
                }
            } catch (RuntimeException e) {
                OteTV.LOGGER.warn("Dropped unreadable admin channel entry");
            }
        }
        data.adminChannelRevision = tag.getLong("adminChannelRevision");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (incompatible != null) {
            return incompatible.copy();
        }
        tag.putInt("schema", OteTvCore.WORLD_DATA_SCHEMA_VERSION);
        tag.putUUID("namespace", worldNamespace);
        ListTag tvs = new ListTag();
        for (Map.Entry<UUID, TvRecord> e : televisions.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", e.getKey());
            t.putString("dim", e.getValue().dimension().location().toString());
            t.putLong("pos", e.getValue().pos().asLong());
            if (e.getValue().owner() != null) {
                t.putUUID("owner", e.getValue().owner());
            }
            t.putString("model", e.getValue().model());
            tvs.add(t);
        }
        tag.put("televisions", tvs);
        ListTag channels = new ListTag();
        for (ChannelDefinition def : adminChannels) {
            channels.add(StringTag.valueOf(ChannelJson.write(def).toString()));
        }
        tag.put("adminChannels", channels);
        tag.putLong("adminChannelRevision", adminChannelRevision);
        return tag;
    }

    public UUID worldNamespace() {
        return worldNamespace;
    }

    public boolean isLocked() {
        return incompatible != null;
    }

    public void track(UUID id, ResourceKey<Level> dimension, BlockPos pos, @Nullable UUID owner, TvModel model) {
        if (incompatible != null) {
            return;
        }
        TvRecord record = new TvRecord(dimension, pos.immutable(), owner, model.registryName());
        if (!record.equals(televisions.put(id, record))) {
            setDirty();
        }
    }

    public void untrack(UUID id) {
        if (incompatible == null && televisions.remove(id) != null) {
            setDirty();
        }
    }

    @Nullable
    public TvRecord lookup(UUID id) {
        return televisions.get(id);
    }

    public int countOwnedBy(UUID owner) {
        int n = 0;
        for (TvRecord r : televisions.values()) {
            if (owner.equals(r.owner())) {
                n++;
            }
        }
        return n;
    }

    public int televisionCount() {
        return televisions.size();
    }

    public List<ChannelDefinition> adminChannels() {
        return List.copyOf(adminChannels);
    }

    public long adminChannelRevision() {
        return adminChannelRevision;
    }

    public void putAdminChannel(ChannelDefinition def) {
        adminChannels.removeIf(c -> c.id().equals(def.id()) || c.number() == def.number());
        adminChannels.add(def);
        adminChannelRevision++;
        setDirty();
    }

    public boolean removeAdminChannel(int number) {
        boolean removed = adminChannels.removeIf(c -> c.number() == number);
        if (removed) {
            adminChannelRevision++;
            setDirty();
        }
        return removed;
    }
}
