package com.otectus.otetv.core.net;

/** A destination was refused by policy. The message never contains the query string of the URL. */
public final class DestinationBlockedException extends Exception {
    private final AddressCategory category;
    private final String host;

    public DestinationBlockedException(String host, AddressCategory category, String why) {
        super("blocked " + category + " destination for host " + host + (why == null ? "" : " (" + why + ")"));
        this.host = host;
        this.category = category;
    }

    public AddressCategory category() {
        return category;
    }

    public String host() {
        return host;
    }
}
