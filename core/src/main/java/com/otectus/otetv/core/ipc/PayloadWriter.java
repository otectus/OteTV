package com.otectus.otetv.core.ipc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Big-endian payload builder. Strings are u16-length-prefixed UTF-8. */
public final class PayloadWriter {
    private byte[] buf;
    private int pos;

    public PayloadWriter() {
        this(64);
    }

    public PayloadWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
        }
    }

    public PayloadWriter u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public PayloadWriter bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public PayloadWriter u16(int v) {
        ensure(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public PayloadWriter i32(int v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public PayloadWriter i64(long v) {
        i32((int) (v >>> 32));
        return i32((int) v);
    }

    public PayloadWriter f64(double v) {
        return i64(Double.doubleToLongBits(v));
    }

    public PayloadWriter str(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > 0xFFFF) {
            throw new IllegalArgumentException("string too long");
        }
        u16(b.length);
        return bytes(b, 0, b.length);
    }

    public PayloadWriter bytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    public int size() {
        return pos;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    /** The complete frame including the {@code [length][type]} header. */
    public byte[] toFrame(IpcType type) {
        if (pos > type.maxPayload()) {
            throw new IllegalStateException(type + " payload " + pos + " exceeds " + type.maxPayload());
        }
        int length = pos + 1;
        byte[] frame = new byte[4 + length];
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        frame[4] = (byte) type.code();
        System.arraycopy(buf, 0, frame, 5, pos);
        return frame;
    }
}
