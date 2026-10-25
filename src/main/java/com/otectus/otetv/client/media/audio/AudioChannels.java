package com.otectus.otetv.client.media.audio;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Maps OteTV sound instances to the OpenAL channel Minecraft assigned them (captured from Forge's
 * PlayStreamingSourceEvent) so the audio clock can read the queue position. Only reads OpenAL state.
 */
public final class AudioChannels {
    private static final Map<SoundInstance, Channel> CHANNELS = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private AudioChannels() {
    }

    public static void onStreamingSource(SoundInstance instance, Channel channel) {
        if (instance instanceof TvSoundInstance) {
            CHANNELS.put(instance, channel);
        }
    }

    public static void forget(SoundInstance instance) {
        CHANNELS.remove(instance);
    }

    /** Queue state of the instance's source: {buffersQueued, sampleOffset, playing?1:0}, or null when unknown. */
    @Nullable
    public static int[] queueState(SoundInstance instance) {
        Channel channel = CHANNELS.get(instance);
        if (channel == null) {
            return null;
        }
        int source = channel.source;
        if (source == 0 || !AL10.alIsSource(source)) {
            return null;
        }
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        AL10.alGetError(); // never leave an error behind for Minecraft's own checks
        return new int[]{queued, offset, state == AL10.AL_PLAYING ? 1 : 0};
    }
}
