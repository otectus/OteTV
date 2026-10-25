package com.otectus.otetv.core.ipc;

/** Explicit byte order for frame transport. RGBA comes from GStreamer; BGRA is CEF's native paint order. */
public enum PixelFormat {
    RGBA(4),
    BGRA(4);

    private final int bytesPerPixel;

    PixelFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }
}
