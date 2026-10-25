package com.otectus.otetv.core.capability;

import java.util.Locale;

/** Every control the remote, TV menu and viewing screen can offer (section 6.2). */
public enum PlayerAction {
    POWER(Authority.SHARED),
    HOME(Authority.LOCAL),
    BACK(Authority.LOCAL),
    NAVIGATE(Authority.LOCAL),
    SELECT(Authority.LOCAL),
    PLAY_PAUSE(Authority.SHARED),
    STOP(Authority.SHARED),
    SEEK_BACK(Authority.SHARED),
    SEEK_FORWARD(Authority.SHARED),
    CHANNEL_PREV(Authority.SHARED),
    CHANNEL_NEXT(Authority.SHARED),
    CAPTIONS(Authority.LOCAL),
    SPEAKER_VOLUME(Authority.SHARED),
    LOCAL_VOLUME(Authority.LOCAL),
    LOCAL_MUTE(Authority.LOCAL),
    JOIN(Authority.LOCAL),
    LEAVE(Authority.LOCAL),
    REJOIN_POSITION(Authority.LOCAL),
    CHANGE_SOURCE(Authority.SHARED),
    OPEN_EXTERNAL(Authority.LOCAL),
    SETTINGS(Authority.SHARED),
    TRUST(Authority.SHARED);

    /** Who decides: the server-approved controller, or this viewer alone (section 6.2 table). */
    public enum Authority { SHARED, LOCAL }

    private final Authority authority;

    PlayerAction(Authority authority) {
        this.authority = authority;
    }

    public Authority authority() {
        return authority;
    }

    public String translationKey() {
        return "otetv.action." + name().toLowerCase(Locale.ROOT);
    }
}
