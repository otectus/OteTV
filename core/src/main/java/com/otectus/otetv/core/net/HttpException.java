package com.otectus.otetv.core.net;

import java.io.IOException;

/** A protocol-level HTTP failure: bad status, malformed headers, oversized body or a refused redirect. */
public final class HttpException extends IOException {
    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
