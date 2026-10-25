package com.otectus.otetv.client;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.client.media.audio.AudioChannels;
import com.otectus.otetv.client.render.PlacementPreview;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlayStreamingSourceEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client gameplay events on the Forge bus. */
@Mod.EventBusSubscriber(modid = OteTV.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static final Map<BlockPos, SoundInstance> HUMS = new HashMap<>();

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientTvManager.get().tick();
        OteTvKeys.handle();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getGameTime() % 20 == 0) {
            updateHums(mc);
        }
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            PlacementPreview.render(event.getPoseStack(), event.getCamera().getPosition());
        }
    }

    @SubscribeEvent
    public static void onStreamingSource(PlayStreamingSourceEvent event) {
        AudioChannels.onStreamingSource(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientHooks.reset();
        stopHums();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            // Dimension change or disconnect: audio stops and every resource is released (section 10.5).
            ClientHooks.reset();
            stopHums();
        }
    }

    /** Optional quiet hum near powered CRT-era sets; off by default and silenced by the accessibility setting. */
    private static void updateHums(Minecraft mc) {
        if (!OteTvClientConfig.CRT_HUM.get() || mc.player == null) {
            stopHums();
            return;
        }
        Iterator<Map.Entry<BlockPos, SoundInstance>> it = HUMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, SoundInstance> e = it.next();
            boolean keep = mc.level.getBlockEntity(e.getKey()) instanceof TvBlockEntity be && be.publicState().powered()
                    && e.getKey().distSqr(mc.player.blockPosition()) < 12 * 12;
            if (!keep || !mc.getSoundManager().isActive(e.getValue())) {
                mc.getSoundManager().stop(e.getValue());
                it.remove();
            }
        }
        BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -4, -8), center.offset(8, 4, 8))) {
            if (HUMS.size() >= 4) {
                break;
            }
            if (mc.level.getBlockEntity(pos) instanceof TvBlockEntity be && be.publicState().powered()
                    && be.model().ordinal() <= TvModel.CRT.ordinal() && !HUMS.containsKey(pos)) {
                SoundInstance hum = new SimpleSoundInstance(ModSounds.CRT_HUM.get().getLocation(), SoundSource.BLOCKS, 0.08f, 1.0f,
                        RandomSource.create(), true, 0, SoundInstance.Attenuation.LINEAR, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, false);
                mc.getSoundManager().play(hum);
                HUMS.put(pos.immutable(), hum);
            }
        }
    }

    private static void stopHums() {
        Minecraft mc = Minecraft.getInstance();
        for (SoundInstance s : HUMS.values()) {
            mc.getSoundManager().stop(s);
        }
        HUMS.clear();
    }
}
