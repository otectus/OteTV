package com.otectus.otetv.core.provider;

import java.util.List;
import java.util.Locale;

/**
 * The four subscription services named in the brief (section 13). Each opens in the player's own browser only;
 * none is in-world playback, and no tile may carry a functioning-looking Play button. Statuses and evidence live in
 * docs/provider-support.md and are mirrored here so the in-game Services tab stays truthful.
 */
public enum SubscriptionService {
    NETFLIX("Netflix", "https://www.netflix.com/"),
    HULU("Hulu", "https://www.hulu.com/"),
    CRUNCHYROLL("Crunchyroll", "https://www.crunchyroll.com/"),
    PRIME_VIDEO("Prime Video", "https://www.primevideo.com/");

    /** The eight separately evaluated capabilities of section 13.1. */
    public enum Capability {
        WEBSITE_NAVIGATION, ACCOUNT_LOGIN, ENTITLED_PLAYBACK, EMBEDDED_RUNTIME_PLAYBACK, CDM_DISTRIBUTION,
        PERMITTED_TEXTURE_OUTPUT, USER_CONTROL, MULTIPLAYER_SYNC
    }

    public enum Result { NOT_TESTED, PASSED_EXTERNAL_BROWSER_ONLY, BLOCKED }

    private final String displayName;
    private final String homepage;

    SubscriptionService(String displayName, String homepage) {
        this.displayName = displayName;
        this.homepage = homepage;
    }

    public String displayName() {
        return displayName;
    }

    /** Fixed, compiled-in homepage. Never taken from a server, channel or resource pack. */
    public String homepage() {
        return homepage;
    }

    public ProviderStatus status() {
        return ProviderStatus.EXTERNAL_ONLY;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Capability results recorded for this release; see docs/provider-support.md for method and retest triggers. */
    public List<Result> capabilityResults() {
        // Website navigation works only through the player's own system browser; every in-world stage is untested
        // or blocked pending provider evidence and agreements (section 13.6).
        return List.of(Result.PASSED_EXTERNAL_BROWSER_ONLY, Result.NOT_TESTED, Result.NOT_TESTED, Result.NOT_TESTED,
                Result.BLOCKED, Result.BLOCKED, Result.NOT_TESTED, Result.BLOCKED);
    }
}
