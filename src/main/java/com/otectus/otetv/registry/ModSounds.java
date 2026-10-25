package com.otectus.otetv.registry;

import com.otectus.otetv.OteTV;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Original synthesized sounds (tools/assets/generate_sounds.py). Startup sounds and hum can be disabled locally. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, OteTV.MOD_ID);

    public static final RegistryObject<SoundEvent> POWER_ON = register("tv.power_on");
    public static final RegistryObject<SoundEvent> POWER_OFF = register("tv.power_off");
    public static final RegistryObject<SoundEvent> CRT_STARTUP = register("tv.crt_startup");
    public static final RegistryObject<SoundEvent> CHANNEL_CLICK = register("tv.channel_click");
    public static final RegistryObject<SoundEvent> KNOB_TURN = register("tv.knob_turn");
    public static final RegistryObject<SoundEvent> CRT_HUM = register("tv.crt_hum");
    public static final RegistryObject<SoundEvent> REMOTE_BEEP = register("remote.beep");
    public static final RegistryObject<SoundEvent> REMOTE_PAIR = register("remote.pair");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(OteTV.id(name)));
    }

    private ModSounds() {
    }
}
