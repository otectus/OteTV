package com.otectus.otetv.core.provider;

import java.util.Locale;

/** Provider qualification status from section 13.6. Feature flags default off until VERIFIED evidence exists. */
public enum ProviderStatus {
    UNTESTED,
    RESEARCHING,
    EXTERNAL_ONLY,
    BLOCKED,
    EXPERIMENTAL,
    VERIFIED;

    public String translationKey() {
        return "otetv.provider_status." + name().toLowerCase(Locale.ROOT);
    }
}
