package com.otectus.otetv;

import com.otectus.otetv.api.OteTvApi;
import com.otectus.otetv.channel.ChannelCatalog;
import com.otectus.otetv.channel.ChannelReloadListener;
import com.otectus.otetv.command.OteTvCommand;
import com.otectus.otetv.session.TvSessionService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Gameplay events on the Forge bus (server side, both dedicated and integrated). */
@Mod.EventBusSubscriber(modid = OteTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    private ServerEvents() {
    }

    /**
     * Starts before any level loads: spawn chunks (and the controllers in them) are loaded ahead of
     * {@code ServerStartingEvent}, and a controller that loads with no service never gets a session.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        TvSessionService.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ChannelCatalog.rebuild(event.getServer());
        if (!OteTvApi.programmaticChannels().isEmpty()) {
            OteTV.LOGGER.info("{} programmatic channel(s) registered through the API", OteTvApi.programmaticChannels().size());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TvSessionService.stop();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ChannelCatalog.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TvSessionService svc = TvSessionService.get();
            if (svc != null) {
                svc.tick();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TvSessionService svc = TvSessionService.get();
        if (svc != null && event.getEntity() instanceof ServerPlayer player) {
            svc.onPlayerLeft(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        TvSessionService svc = TvSessionService.get();
        if (svc != null && event.getEntity() instanceof ServerPlayer player) {
            svc.onPlayerLeft(player);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        OteTvCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ChannelReloadListener(ServerLifecycleHooks::getCurrentServer));
    }
}
