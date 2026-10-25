package com.otectus.otetv.core.net;

import java.util.Locale;

/** Classification of a resolved network address. Only {@link #PUBLIC} is reachable without an explicit local grant. */
public enum AddressCategory {
    PUBLIC(false),
    LOOPBACK(true),
    PRIVATE(true),
    UNIQUE_LOCAL(true),
    CARRIER_GRADE_NAT(true),
    LINK_LOCAL(true),
    CLOUD_METADATA(false),
    MULTICAST(false),
    BROADCAST(false),
    UNSPECIFIED(false),
    DOCUMENTATION(false),
    BENCHMARKING(false),
    RESERVED(false);

    private final boolean grantable;

    AddressCategory(boolean grantable) {
        this.grantable = grantable;
    }

    /** Whether an explicit, user-created LAN grant for a specific host and port may open this category. */
    public boolean isGrantable() {
        return grantable;
    }

    public String translationKey() {
        return "otetv.address_category." + name().toLowerCase(Locale.ROOT);
    }
}
