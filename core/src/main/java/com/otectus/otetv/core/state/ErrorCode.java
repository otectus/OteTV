package com.otectus.otetv.core.state;

import java.util.Locale;

/**
 * Local playback failures with actionable messages (section 7.4). Technical detail goes to the expandable diagnostic
 * panel; the translation key gives the player-facing sentence and the {@link Action} the UI offers next.
 */
public enum ErrorCode {
    NONE(Action.NONE),
    RUNTIME_MISSING(Action.INSTALL_RUNTIME),
    RUNTIME_INCOMPATIBLE(Action.INSTALL_RUNTIME),
    LOCAL_FILE_ABSENT(Action.SELECT_FILE),
    LOCAL_FILE_MISMATCH(Action.SELECT_FILE),
    UNSUPPORTED_CODEC(Action.DIAGNOSTICS),
    UNSUPPORTED_CONTAINER(Action.DIAGNOSTICS),
    MEDIA_MALFORMED(Action.RETRY),
    NETWORK(Action.RETRY),
    HTTP_STATUS(Action.RETRY),
    TIMEOUT(Action.RETRY),
    POLICY_BLOCKED_HOST(Action.DIAGNOSTICS),
    NOT_MEDIA(Action.DIAGNOSTICS),
    HLS_ENCRYPTED(Action.NONE),
    HLS_UNSUPPORTED(Action.DIAGNOSTICS),
    RANGE_UNSUPPORTED(Action.NONE),
    YOUTUBE_EMBED_DISABLED(Action.OPEN_EXTERNAL),
    YOUTUBE_UNAVAILABLE(Action.OPEN_EXTERNAL),
    YOUTUBE_INVALID(Action.NONE),
    YOUTUBE_HTML5(Action.OPEN_EXTERNAL),
    YOUTUBE_IDENTITY(Action.RETRY),
    AUTOPLAY_BLOCKED(Action.PRESS_PLAY),
    BROWSER_UNAVAILABLE(Action.OPEN_EXTERNAL),
    SUBSCRIPTION_EXTERNAL(Action.OPEN_EXTERNAL),
    WORKER_CRASH(Action.RETRY),
    WORKER_HUNG(Action.RETRY),
    CONSENT_REQUIRED(Action.CONSENT),
    EXTERNAL_MEDIA_DISABLED(Action.NONE),
    TOO_MANY_SESSIONS(Action.NONE),
    INTERNAL(Action.DIAGNOSTICS);

    public enum Action {
        NONE, RETRY, INSTALL_RUNTIME, SELECT_FILE, OPEN_EXTERNAL, PRESS_PLAY, CONSENT, DIAGNOSTICS
    }

    private final Action action;

    ErrorCode(Action action) {
        this.action = action;
    }

    public Action action() {
        return action;
    }

    public String translationKey() {
        return "otetv.error." + name().toLowerCase(Locale.ROOT);
    }

    public static ErrorCode byOrdinal(int ordinal) {
        ErrorCode[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : INTERNAL;
    }

    /** Maps documented IFrame Player API error codes (section 12.3). */
    public static ErrorCode fromYouTubeError(int code) {
        return switch (code) {
            case 2 -> YOUTUBE_INVALID;
            case 5 -> YOUTUBE_HTML5;
            case 100 -> YOUTUBE_UNAVAILABLE;
            case 101, 150 -> YOUTUBE_EMBED_DISABLED;
            case 153 -> YOUTUBE_IDENTITY;
            default -> INTERNAL;
        };
    }
}
