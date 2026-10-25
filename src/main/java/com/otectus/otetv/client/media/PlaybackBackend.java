package com.otectus.otetv.client.media;

import com.otectus.otetv.client.media.audio.TvAudioOutput;
import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Asynchronous playback adapter (section 8.1 PlaybackBackend): prepare/start/pause/seek/stop/close, local volume and
 * input, and observed status. Every command carries the session's flush generation; results from older generations
 * are discarded by the implementation. Status calls never block the render thread.
 */
public interface PlaybackBackend extends AutoCloseable {
    enum Kind { DIRECT_MEDIA, BROWSER }

    record Status(LocalPlaybackState state, ErrorCode error, String detail, long positionMs, long durationMs, boolean live,
                  PlaybackCapabilities capabilities, int videoWidth, int videoHeight, boolean hasAudio, boolean hasVideo,
                  int bufferingPercent, boolean ended) {
    }

    Kind kind();

    void play();

    void pause();

    /** Flushing seek; {@code generation} becomes the only accepted generation for frames and audio. */
    void seek(long positionMs, int generation);

    void setRate(double rate);

    void setVideoEnabled(boolean enabled);

    void setVisible(boolean visible);

    void setVolume(float volume, boolean muted);

    void setCaptions(boolean enabled);

    void mouse(int kind, int x, int y, int button, int modifiers, int wheel);

    void key(int kind, int keyCode, int scanCode, int modifiers, int codePoint);

    void resize(int width, int height);

    /** Heartbeat, credit and clock reporting; called every client tick. */
    void tick();

    Status status();

    /** Best current position: the audio clock when audio is present, otherwise the backend's own report. */
    long observedPositionMs();

    FrameExchange frames();

    @Nullable
    TvAudioOutput audio();

    List<Cue> cues();

    /** Redacted technical details for the diagnostics panel. */
    List<String> diagnostics();

    @Override
    void close();
}
