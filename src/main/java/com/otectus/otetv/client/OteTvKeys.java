package com.otectus.otetv.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.otectus.otetv.client.screen.ViewingScreen;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.item.RemoteControlItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.Optional;

/** Remappable OteTV keys (section 7.3): open the held remote, mute the nearest TV for me, enlarged viewing. */
public final class OteTvKeys {
    private static final String CATEGORY = "key.categories.otetv";
    public static final KeyMapping OPEN_REMOTE = new KeyMapping("key.otetv.open_remote", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);
    public static final KeyMapping LOCAL_MUTE = new KeyMapping("key.otetv.local_mute", InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static final KeyMapping VIEWING_MODE = new KeyMapping("key.otetv.viewing_mode", InputConstants.UNKNOWN.getValue(), CATEGORY);

    private OteTvKeys() {
    }

    static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_REMOTE);
        event.register(LOCAL_MUTE);
        event.register(VIEWING_MODE);
    }

    private static Optional<ClientPlaybackSession> nearest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return Optional.empty();
        }
        return ClientTvManager.get().allSessions().stream()
                .min(Comparator.comparingDouble(s -> s.audioPosition().distanceToSqr(mc.player.position())));
    }

    public static void handle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        while (OPEN_REMOTE.consumeClick()) {
            if (mc.player.getMainHandItem().getItem() instanceof RemoteControlItem) {
                ClientHooks.openRemote(InteractionHand.MAIN_HAND);
            } else if (mc.player.getOffhandItem().getItem() instanceof RemoteControlItem) {
                ClientHooks.openRemote(InteractionHand.OFF_HAND);
            } else {
                mc.player.displayClientMessage(Component.translatable("otetv.remote.hold_remote"), true);
            }
        }
        while (LOCAL_MUTE.consumeClick()) {
            nearest().ifPresent(s -> {
                s.setLocalMute(!s.isLocalMuted());
                mc.player.displayClientMessage(Component.translatable(s.isLocalMuted() ? "otetv.remote.muted_for_you" : "otetv.remote.unmuted_for_you"), true);
            });
        }
        while (VIEWING_MODE.consumeClick()) {
            nearest().ifPresent(s -> mc.setScreen(new ViewingScreen(null, s.tvId())));
        }
    }
}
