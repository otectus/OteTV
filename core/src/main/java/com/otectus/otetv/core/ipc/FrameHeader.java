package com.otectus.otetv.core.ipc;

import com.otectus.otetv.core.Limits;

/**
 * Descriptor preceding each decoded frame (section 14.1): source generation, sequence, presentation timestamp,
 * dimensions, stride and pixel format. {@link #validate} uses overflow-checked arithmetic and runs before any copy.
 */
public record FrameHeader(int generation, int sequence, long ptsNanos, int width, int height, int stride, PixelFormat format) {
    public static final int BYTES = 4 + 4 + 8 + 4 + 4 + 4 + 1;
    /** Browser frames may be up to 4K internally for crisp enlarged viewing; direct media is capped lower. */
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;

    public void write(PayloadWriter w) {
        w.i32(generation).i32(sequence).i64(ptsNanos).i32(width).i32(height).i32(stride).u8(format.ordinal());
    }

    public static FrameHeader read(PayloadReader r) throws IpcProtocolException {
        int generation = r.i32();
        int sequence = r.i32();
        long pts = r.i64();
        int width = r.i32(1, MAX_WIDTH);
        int height = r.i32(1, MAX_HEIGHT);
        int stride = r.i32(1, MAX_WIDTH * 4 + 256);
        PixelFormat format = r.enumValue(PixelFormat.values());
        return new FrameHeader(generation, sequence, pts, width, height, stride, format);
    }

    /** Bytes of pixel data that must follow the header. */
    public int pixelBytes() throws IpcProtocolException {
        try {
            return Math.multiplyExact(stride, height);
        } catch (ArithmeticException e) {
            throw new IpcProtocolException("frame size overflow");
        }
    }

    /** Checks stride/format consistency and that the payload carries exactly the declared pixels. */
    public void validate(int payloadLength, boolean directMedia) throws IpcProtocolException {
        if (directMedia && (width > Limits.MAX_FRAME_WIDTH || height > Limits.MAX_FRAME_HEIGHT)) {
            throw new IpcProtocolException("frame exceeds output ceiling: " + width + "x" + height);
        }
        int minStride;
        try {
            minStride = Math.multiplyExact(width, format.bytesPerPixel());
        } catch (ArithmeticException e) {
            throw new IpcProtocolException("stride overflow");
        }
        if (stride < minStride || stride > minStride + 256 || stride % 4 != 0) {
            throw new IpcProtocolException("bad stride " + stride + " for width " + width);
        }
        long expected = (long) BYTES + pixelBytes();
        if (expected != payloadLength) {
            throw new IpcProtocolException("frame payload " + payloadLength + " != expected " + expected);
        }
    }
}
