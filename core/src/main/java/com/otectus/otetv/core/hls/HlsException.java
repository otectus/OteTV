package com.otectus.otetv.core.hls;

/** A playlist was malformed, too large, encrypted or otherwise outside the supported HLS subset. */
public final class HlsException extends Exception {
    public enum Reason { MALFORMED, TOO_LARGE, ENCRYPTED, UNSUPPORTED, BAD_URI }

    private final Reason reason;

    public HlsException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
