package com.otectus.otetv.worker.media;

import java.io.Closeable;
import java.io.IOException;

/**
 * The only way media bytes enter the decoder: the worker feeds GStreamer through appsrc from one of these, so no
 * native element ever opens a file or URL by itself (section 11.3).
 */
interface ByteSource extends Closeable {
    /** Total length in bytes, or -1 if unknown. */
    long size();

    /** Whether {@link #seek} works (local files, or HTTP servers that honour byte ranges). */
    boolean seekable();

    int read(byte[] buf, int off, int len) throws IOException;

    void seek(long position) throws IOException;

    long position();

    /** For segmented sources: media position (ns) where the current stream started, else 0. */
    default long timelineOffsetNanos() {
        return 0;
    }

    default boolean isLive() {
        return false;
    }

    /** Known media duration in ms from container-independent metadata (HLS playlists), or -1. */
    default long knownDurationMs() {
        return -1;
    }
}
