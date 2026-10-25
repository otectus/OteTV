package com.otectus.otetv.client.media.audio;

import com.otectus.otetv.OteTV;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The TV speaker as a Minecraft streaming sound: a mono positional source at the screen centre with linear
 * attenuation over the audible range, or a stereo listener-relative source in the enlarged viewing mode. It plays in
 * the jukebox/record category, so master and that slider apply, and it starts even at zero volume so local mute
 * never desynchronizes the clock.
 */
public final class TvSoundInstance extends AbstractTickableSoundInstance {
    private static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(OteTV.id("tv.stream"));

    private final TvAudioStream stream;
    private final Supplier<Float> volumeSupplier;
    private final Supplier<Vec3> positionSupplier;
    private final int range;

    public TvSoundInstance(TvAudioStream stream, boolean stereoRelative, int range, Supplier<Float> volume, Supplier<Vec3> position) {
        super(EVENT, SoundSource.RECORDS, RandomSource.create());
        this.stream = stream;
        this.volumeSupplier = volume;
        this.positionSupplier = position;
        this.range = range;
        this.looping = false;
        this.delay = 0;
        this.relative = stereoRelative;
        this.attenuation = stereoRelative ? SoundInstance.Attenuation.NONE : SoundInstance.Attenuation.LINEAR;
        this.volume = Math.min(1f, volume.get());
        Vec3 p = position.get();
        this.x = stereoRelative ? 0 : p.x;
        this.y = stereoRelative ? 0 : p.y;
        this.z = stereoRelative ? 0 : p.z;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        // A synthetic streaming sound: no sounds.json entry and no resource-pack replacement of the PCM path.
        this.sound = new Sound(OteTV.id("tv/stream").toString(), ConstantFloat.of(1f), ConstantFloat.of(1f), 1,
                Sound.Type.FILE, true, false, range);
        WeighedSoundEvents events = new WeighedSoundEvents(getLocation(), null);
        events.addSound(this.sound);
        return events;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return true;
    }

    @Override
    public void tick() {
        if (stream.isClosed()) {
            stop();
            return;
        }
        this.volume = Math.max(0f, Math.min(1f, volumeSupplier.get()));
        if (!relative) {
            Vec3 p = positionSupplier.get();
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
        }
    }

    public TvAudioStream stream() {
        return stream;
    }

    public void stopNow() {
        stop();
    }
}
