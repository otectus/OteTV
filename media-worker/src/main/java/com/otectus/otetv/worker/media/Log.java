package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.redact.Redactor;

import java.io.PrintStream;

/** Logs go to stderr only; stdout carries the framed protocol and nothing else (section 16.3). */
final class Log {
    private static final PrintStream ERR = System.err;

    private Log() {
    }

    static void info(String message) {
        ERR.println("[otetv-media] " + Redactor.redact(message));
    }

    static void warn(String message, Throwable t) {
        ERR.println("[otetv-media] WARN " + Redactor.redact(message + (t == null ? "" : ": " + t)));
    }
}
