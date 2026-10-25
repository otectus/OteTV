package com.otectus.otetv.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.TvMount;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resource-pack overridable visual profiles ({@code assets/otetv/otetv/profiles/<model>.json}). A pack may move the
 * aperture, toggle period effects and place indicators within validated bounds; it can never add behaviour, change
 * collision or touch network trust (section 20.2). Invalid profiles fall back to the built-in definition.
 */
public final class TvProfiles implements ResourceManagerReloadListener {
    private static final Map<TvModel, Map<TvMount, TvProfile>> BUILT_IN = new EnumMap<>(TvModel.class);
    private static final Map<TvModel, Map<TvMount, TvProfile>> ACTIVE = new EnumMap<>(TvModel.class);

    static {
        put(TvModel.WOODEN_MONOCHROME, TvMount.STAND, new TvProfile(new TvProfile.Aperture(4, 14, 18, 24.5, 1.45), true, true, true,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.DIAL, 23.5, 7.5, 0.45, 3.0))));
        put(TvModel.WOODGRAIN_COLOR, TvMount.STAND, new TvProfile(new TvProfile.Aperture(4, 4.5, 16, 13.5, 1.7), false, true, true,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.DIAL, 25, 10, 1.45, 5.0))));
        put(TvModel.PORTABLE, TvMount.STAND, new TvProfile(new TvProfile.Aperture(3, 3.5, 11, 9.5, 3.7), false, true, true,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.DIAL, 12.5, 2.0, 3.65, 1.8))));
        put(TvModel.CRT, TvMount.STAND, new TvProfile(new TvProfile.Aperture(4, 8.5, 28, 26.5, 0.7), false, true, true,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.LED, 27.5, 4.5, 0.95, 1.0))));
        put(TvModel.PLASMA, TvMount.STAND, new TvProfile(new TvProfile.Aperture(-12.8, 6.9, 28.8, 30.3, 5.75), false, false, false,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.LED, 8, 6.2, 5.95, 0.6))));
        put(TvModel.PLASMA, TvMount.WALL, new TvProfile(new TvProfile.Aperture(-12.8, 5.4, 28.8, 28.8, 10.75), false, false, false,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.LED, 8, 4.7, 10.95, 0.6))));
        put(TvModel.OLED, TvMount.STAND, new TvProfile(new TvProfile.Aperture(-15.2, 3.45, 31.2, 29.55, 6.9), false, false, false,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.LED, 8, 3.2, 6.95, 0.4))));
        put(TvModel.OLED, TvMount.WALL, new TvProfile(new TvProfile.Aperture(-15.2, 3.45, 31.2, 29.55, 14.4), false, false, false,
                List.of(new TvProfile.Indicator(TvProfile.IndicatorType.LED, 8, 3.2, 14.45, 0.4))));
        BUILT_IN.forEach((m, map) -> ACTIVE.put(m, new EnumMap<>(map)));
    }

    private static void put(TvModel model, TvMount mount, TvProfile profile) {
        BUILT_IN.computeIfAbsent(model, k -> new EnumMap<>(TvMount.class)).put(mount, profile);
    }

    public static TvProfile get(TvModel model, TvMount mount) {
        Map<TvMount, TvProfile> map = ACTIVE.get(model);
        TvProfile p = map.get(model.isWallMountable() ? mount : TvMount.STAND);
        return p != null ? p : map.get(TvMount.STAND);
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        for (TvModel model : TvModel.values()) {
            Map<TvMount, TvProfile> map = new EnumMap<>(BUILT_IN.get(model));
            ResourceLocation loc = OteTV.id("otetv/profiles/" + model.registryName() + ".json");
            Optional<Resource> resource = manager.getResource(loc);
            if (resource.isPresent()) {
                try (Reader reader = resource.get().openAsReader()) {
                    JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
                    for (TvMount mount : TvMount.values()) {
                        String key = mount.getSerializedName();
                        if (o.has(key)) {
                            TvProfile base = map.getOrDefault(mount, map.get(TvMount.STAND));
                            TvProfile parsed = parse(o.getAsJsonObject(key), base, model, mount);
                            if (parsed != null) {
                                map.put(mount, parsed);
                            }
                        }
                    }
                } catch (Exception e) {
                    OteTV.LOGGER.warn("Ignoring invalid TV profile {}: {}", loc, e.getMessage());
                }
            }
            ACTIVE.put(model, map);
        }
    }

    private static TvProfile parse(JsonObject o, TvProfile base, TvModel model, TvMount mount) {
        TvProfile.Aperture aperture = base.aperture();
        if (o.has("aperture")) {
            JsonArray a = o.getAsJsonArray("aperture");
            if (a.size() != 5) {
                return null;
            }
            TvProfile.Aperture candidate = new TvProfile.Aperture(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
                    a.get(3).getAsDouble(), a.get(4).getAsDouble());
            if (!TvProfile.withinModel(model, mount, candidate)) {
                OteTV.LOGGER.warn("TV profile aperture for {} is outside the model bounds; ignored", model.registryName());
                return null;
            }
            aperture = candidate;
        }
        boolean mono = o.has("monochrome") ? o.get("monochrome").getAsBoolean() : base.monochrome();
        boolean scan = o.has("scanlines") ? o.get("scanlines").getAsBoolean() : base.scanlines();
        boolean vig = o.has("vignette") ? o.get("vignette").getAsBoolean() : base.vignette();
        List<TvProfile.Indicator> indicators = base.indicators();
        if (o.has("indicators")) {
            indicators = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("indicators")) {
                JsonObject i = e.getAsJsonObject();
                TvProfile.IndicatorType type = TvProfile.IndicatorType.valueOf(i.get("type").getAsString().toUpperCase(Locale.ROOT));
                double size = Math.max(0.2, Math.min(8, i.get("size").getAsDouble()));
                indicators.add(new TvProfile.Indicator(type, i.get("x").getAsDouble(), i.get("y").getAsDouble(), i.get("z").getAsDouble(), size));
                if (indicators.size() >= 8) {
                    break;
                }
            }
        }
        return new TvProfile(aperture, mono, scan, vig, List.copyOf(indicators));
    }
}
