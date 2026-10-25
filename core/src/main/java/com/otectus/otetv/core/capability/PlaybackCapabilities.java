package com.otectus.otetv.core.capability;

import com.otectus.otetv.core.source.SourceType;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a backend and the currently loaded content can actually do (section 8.1/8.2). Capabilities are established by
 * the backend after metadata loads and can change at runtime; the UI intersects them with source policy, local
 * preference and server permission before exposing an action.
 */
public final class PlaybackCapabilities {
    public enum Flag {
        PLAY, PAUSE, SEEK, LIVE, DVR, RATE_CONTROL, AUDIO_TRACKS, TEXT_TRACKS, CAPTIONS, TEXTURE_OUTPUT,
        EFFECTS_ELIGIBLE, FRAME_SYNC, BEST_EFFORT_SYNC, HAS_AUDIO, HAS_VIDEO, LOCAL_VOLUME
    }

    public static final PlaybackCapabilities NONE = new PlaybackCapabilities(EnumSet.noneOf(Flag.class), -1);
    public static final PlaybackCapabilities ALL = new PlaybackCapabilities(EnumSet.allOf(Flag.class), -1);

    private final EnumSet<Flag> flags;
    private final long durationMs;

    private PlaybackCapabilities(EnumSet<Flag> flags, long durationMs) {
        this.flags = flags;
        this.durationMs = durationMs;
    }

    public static PlaybackCapabilities of(Set<Flag> flags, long durationMs) {
        EnumSet<Flag> copy = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        return new PlaybackCapabilities(copy, durationMs < 0 ? -1 : durationMs);
    }

    public static PlaybackCapabilities fromBits(long bits, long durationMs) {
        EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
        for (Flag f : Flag.values()) {
            if ((bits & (1L << f.ordinal())) != 0) {
                set.add(f);
            }
        }
        return new PlaybackCapabilities(set, durationMs < 0 ? -1 : durationMs);
    }

    public long toBits() {
        long bits = 0;
        for (Flag f : flags) {
            bits |= 1L << f.ordinal();
        }
        return bits;
    }

    /**
     * What a source type can offer in principle, before a backend reports anything. YouTube never offers effects
     * or rate control in the initial adapter (sections 5.3 and 10.4).
     */
    public static PlaybackCapabilities sourceDefaults(SourceType type) {
        EnumSet<Flag> f = EnumSet.of(Flag.PLAY, Flag.TEXTURE_OUTPUT, Flag.HAS_VIDEO, Flag.HAS_AUDIO, Flag.LOCAL_VOLUME);
        switch (type) {
            case BUNDLED, SHARED_LOCAL_ASSET, LOCAL_FILE, HTTPS_VIDEO -> {
                f.addAll(EnumSet.of(Flag.PAUSE, Flag.SEEK, Flag.RATE_CONTROL, Flag.AUDIO_TRACKS, Flag.TEXT_TRACKS,
                        Flag.CAPTIONS, Flag.EFFECTS_ELIGIBLE, Flag.FRAME_SYNC));
            }
            case HLS_CLEAR -> f.addAll(EnumSet.of(Flag.PAUSE, Flag.SEEK, Flag.LIVE, Flag.CAPTIONS, Flag.EFFECTS_ELIGIBLE, Flag.FRAME_SYNC));
            case YOUTUBE -> f.addAll(EnumSet.of(Flag.PAUSE, Flag.SEEK, Flag.CAPTIONS, Flag.BEST_EFFORT_SYNC));
        }
        return new PlaybackCapabilities(f, -1);
    }

    public boolean has(Flag flag) {
        return flags.contains(flag);
    }

    public Set<Flag> flags() {
        return EnumSet.copyOf(flags.isEmpty() ? EnumSet.noneOf(Flag.class) : flags);
    }

    public long durationMs() {
        return durationMs;
    }

    public boolean isLive() {
        return flags.contains(Flag.LIVE);
    }

    public PlaybackCapabilities intersect(PlaybackCapabilities other) {
        EnumSet<Flag> set = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        set.retainAll(other.flags);
        // LIVE is a property, not a permission: keep it if either side knows the content is live.
        if (flags.contains(Flag.LIVE) || other.flags.contains(Flag.LIVE)) {
            set.add(Flag.LIVE);
        }
        long d = durationMs >= 0 ? durationMs : other.durationMs;
        return new PlaybackCapabilities(set, d);
    }

    public PlaybackCapabilities with(Flag flag, boolean present) {
        EnumSet<Flag> set = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        if (present) {
            set.add(flag);
        } else {
            set.remove(flag);
        }
        return new PlaybackCapabilities(set, durationMs);
    }

    public PlaybackCapabilities withDuration(long newDurationMs) {
        return new PlaybackCapabilities(flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags), newDurationMs < 0 ? -1 : newDurationMs);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlaybackCapabilities p && p.flags.equals(flags) && p.durationMs == durationMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(flags, durationMs);
    }

    @Override
    public String toString() {
        return "Caps" + flags + (durationMs >= 0 ? " " + durationMs + "ms" : "");
    }
}
