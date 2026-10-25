package com.otectus.otetv.core.net;

/** A URL failed syntactic or policy validation. The reason is a stable code suitable for translation keys. */
public final class UrlRejectedException extends Exception {
    public enum Reason {
        EMPTY,
        TOO_LONG,
        ILLEGAL_CHARACTER,
        MALFORMED,
        UNSUPPORTED_SCHEME,
        CREDENTIALS,
        BAD_HOST,
        AMBIGUOUS_NUMERIC_HOST,
        SINGLE_LABEL_HOST,
        BAD_PORT,
        ZONE_ID
    }

    private final Reason reason;

    public UrlRejectedException(Reason reason, String detail) {
        super(reason + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String translationKey() {
        return "otetv.url_rejected." + reason.name().toLowerCase(java.util.Locale.ROOT);
    }
}
