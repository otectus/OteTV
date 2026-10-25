package com.otectus.otetv.core.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation for blocking network work. Cancelling closes every registered socket so a blocked read
 * returns promptly when a source generation changes (section 11.3).
 */
public final class CancellationToken {
    private volatile boolean cancelled;
    private final List<Closeable> resources = new CopyOnWriteArrayList<>();

    public void cancel() {
        cancelled = true;
        for (Closeable resource : resources) {
            closeQuietly(resource);
        }
        resources.clear();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() throws InterruptedIOException {
        if (cancelled) {
            throw new InterruptedIOException("cancelled");
        }
    }

    public void register(Closeable resource) {
        resources.add(resource);
        if (cancelled) {
            closeQuietly(resource);
        }
    }

    public void unregister(Closeable resource) {
        resources.remove(resource);
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
