package com.otectus.otetv.core.net;

/** A destination was refused by policy. The message never contains the query string of the URL. */
public final class DestinationBlockedException extends Exception {
    private final AddressCategory category;
    private final String host;
    private final String reason;

    /**
     * @param category the refused address class, or {@code null} when the URL was refused before any address was resolved
     *                 (plain http without a grant, an https-to-http redirect)
     */
    public DestinationBlockedException(String host, AddressCategory category, String reason) {
        super("blocked " + (category == null ? "" : category + " ") + "destination for host " + host + (reason == null ? "" : " (" + reason + ")"));
        this.host = host;
        this.category = category;
        this.reason = reason;
    }

    /** The refused address class, or {@code null} for refusals that happened before resolution. */
    public AddressCategory category() {
        return category;
    }

    public String host() {
        return host;
    }

    /** Short description for diagnostics: the address class and/or the reason, never the URL itself. */
    public String describe() {
        if (category == null) {
            return reason == null ? "blocked destination" : reason;
        }
        return category + " address" + (reason == null ? "" : " (" + reason + ")");
    }
}
