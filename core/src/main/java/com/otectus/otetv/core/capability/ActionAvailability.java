package com.otectus.otetv.core.capability;

import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.source.SourceType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Intersects backend capability, source capability, provider policy, local preference and server permission into
 * one enabled/disabled decision per {@link PlayerAction}, with the reason shown when disabled (section 8.2). An API
 * method existing in Java is never sufficient on its own to expose a control.
 */
public final class ActionAvailability {
    public record State(boolean enabled, String reasonKey) {
        public static final State ENABLED = new State(true, "");

        static State disabled(String reason) {
            return new State(false, "otetv.disabled." + reason);
        }
    }

    /** Everything the decision depends on. Build one per screen refresh. */
    public record Context(
            Set<TvPermission> permissions,
            boolean leaseHeldByOther,
            boolean canReclaimLease,
            boolean powered,
            SourceType sourceType,
            PlaybackCapabilities backend,
            boolean joined,
            boolean externalMediaDisabled,
            boolean captionsAvailable,
            boolean hasChannels,
            boolean diverged,
            boolean tvAvailable) {
    }

    private ActionAvailability() {
    }

    public static Map<PlayerAction, State> evaluate(Context c) {
        Map<PlayerAction, State> out = new EnumMap<>(PlayerAction.class);
        for (PlayerAction a : PlayerAction.values()) {
            out.put(a, evaluate(a, c));
        }
        return out;
    }

    public static State evaluate(PlayerAction action, Context c) {
        if (!c.tvAvailable && action != PlayerAction.HOME && action != PlayerAction.BACK) {
            return State.disabled("tv_unavailable");
        }
        if (action.authority() == PlayerAction.Authority.SHARED) {
            TvPermission needed = switch (action) {
                case POWER -> TvPermission.POWER;
                case CHANNEL_PREV, CHANNEL_NEXT -> TvPermission.CHANNEL;
                case CHANGE_SOURCE -> TvPermission.SOURCE;
                case SETTINGS -> TvPermission.SETTINGS;
                case TRUST -> TvPermission.ACL;
                default -> TvPermission.PLAYBACK;
            };
            if (!c.permissions.contains(needed)) {
                return State.disabled("no_permission");
            }
            if (c.leaseHeldByOther && !c.canReclaimLease && action != PlayerAction.SETTINGS && action != PlayerAction.TRUST) {
                return State.disabled("lease_held");
            }
        }
        PlaybackCapabilities source = c.sourceType == null ? PlaybackCapabilities.NONE : PlaybackCapabilities.sourceDefaults(c.sourceType);
        PlaybackCapabilities caps = c.backend == null ? source : source.intersect(c.backend);
        return switch (action) {
            case POWER, HOME, BACK, NAVIGATE, SELECT, SETTINGS, TRUST -> State.ENABLED;
            case LOCAL_VOLUME, LOCAL_MUTE -> c.joined ? State.ENABLED : State.disabled("not_joined");
            case SPEAKER_VOLUME -> c.powered ? State.ENABLED : State.disabled("not_powered");
            case CHANGE_SOURCE -> c.powered ? State.ENABLED : State.disabled("not_powered");
            case CHANNEL_PREV, CHANNEL_NEXT -> !c.powered ? State.disabled("not_powered")
                    : c.hasChannels ? State.ENABLED : State.disabled("no_channels");
            case PLAY_PAUSE -> !c.powered ? State.disabled("not_powered")
                    : c.sourceType == null ? State.disabled("no_source")
                    : caps.isLive() && !caps.has(PlaybackCapabilities.Flag.DVR) ? State.disabled("live_no_pause")
                    : caps.has(PlaybackCapabilities.Flag.PAUSE) ? State.ENABLED : State.disabled("unsupported");
            case STOP -> !c.powered ? State.disabled("not_powered")
                    : c.sourceType == null ? State.disabled("no_source") : State.ENABLED;
            case SEEK_BACK, SEEK_FORWARD -> !c.powered ? State.disabled("not_powered")
                    : c.sourceType == null ? State.disabled("no_source")
                    : caps.isLive() && !caps.has(PlaybackCapabilities.Flag.DVR) ? State.disabled("live_no_seek")
                    : caps.has(PlaybackCapabilities.Flag.SEEK) ? State.ENABLED : State.disabled("not_seekable");
            case CAPTIONS -> !c.joined ? State.disabled("not_joined")
                    : c.captionsAvailable ? State.ENABLED : State.disabled("no_captions");
            case JOIN -> c.joined ? State.disabled("already_joined")
                    : !c.powered ? State.disabled("not_powered")
                    : c.sourceType == null ? State.disabled("no_source")
                    : c.externalMediaDisabled && c.sourceType.requiresNetwork() ? State.disabled("external_disabled")
                    : State.ENABLED;
            case LEAVE -> c.joined ? State.ENABLED : State.disabled("not_joined");
            case REJOIN_POSITION -> c.joined && c.diverged ? State.ENABLED : State.disabled("not_diverged");
            case OPEN_EXTERNAL -> c.sourceType == SourceType.YOUTUBE ? State.ENABLED : State.disabled("no_external");
        };
    }
}
