package com.otectus.otetv;

import com.mojang.logging.LogUtils;
import com.otectus.otetv.advancement.ModCriteria;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.registry.ModBlockEntities;
import com.otectus.otetv.registry.ModBlocks;
import com.otectus.otetv.registry.ModCreativeTabs;
import com.otectus.otetv.registry.ModItems;
import com.otectus.otetv.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * OteTV: era televisions, a universal remote, shared channels and client-side playback.
 * <p>
 * The core mod loads, and dedicated servers start, with no playback runtime installed: native media and browser
 * runtimes run only on clients, in separate processes. The server manages furniture, permissions, channels and
 * playback intent; no media bytes ever pass through it.
 */
@Mod(OteTV.MOD_ID)
public final class OteTV {
    public static final String MOD_ID = "otetv";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** Audit trail for shared-state mutations (section 19). */
    public static final Marker AUDIT = MarkerFactory.getMarker("OTETV_AUDIT");

    public OteTV() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, OteTvServerConfig.SPEC, MOD_ID + "-server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, OteTvClientConfig.SPEC, MOD_ID + "-client.toml");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            OteTvNetwork.register();
            ModCriteria.register();
        });
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
