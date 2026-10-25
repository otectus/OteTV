package com.otectus.otetv.core.ipc;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounds-checked big-endian payload parser. Every read validates remaining length; doubles must be finite. */
public final class PayloadReader {
    private final byte[] buf;
    private final int end;
    private int pos;

    public PayloadReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public PayloadReader(byte[] buf, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buf.length) {
            throw new IllegalArgumentException("bad slice");
        }
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
    }

    private void need(int n) throws IpcProtocolException {
        if (n < 0 || end - pos < n) {
            throw new IpcProtocolException("payload truncated");
        }
    }

    public int remaining() {
        return end - pos;
    }

    public int u8() throws IpcProtocolException {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public boolean bool() throws IpcProtocolException {
        int v = u8();
        if (v > 1) {
            throw new IpcProtocolException("bad boolean");
        }
        return v == 1;
    }

    public int u16() throws IpcProtocolException {
        need(2);
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int i32() throws IpcProtocolException {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public int i32(int min, int max) throws IpcProtocolException {
        int v = i32();
        if (v < min || v > max) {
            throw new IpcProtocolException("value out of range: " + v);
        }
        return v;
    }

    public long i64() throws IpcProtocolException {
        long hi = i32() & 0xFFFFFFFFL;
        long lo = i32() & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    public long i64(long min, long max) throws IpcProtocolException {
        long v = i64();
        if (v < min || v > max) {
            throw new IpcProtocolException("value out of range: " + v);
        }
        return v;
    }

    public double f64() throws IpcProtocolException {
        double d = Double.longBitsToDouble(i64());
        if (!Double.isFinite(d)) {
            throw new IpcProtocolException("non-finite number");
        }
        return d;
    }

    public String str(int maxBytes) throws IpcProtocolException {
        int len = u16();
        if (len > maxBytes) {
            throw new IpcProtocolException("string exceeds " + maxBytes);
        }
        need(len);
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, pos, len)).toString();
            pos += len;
            return s;
        } catch (CharacterCodingException e) {
            throw new IpcProtocolException("malformed UTF-8");
        }
    }

    public byte[] bytes(int length) throws IpcProtocolException {
        need(length);
        byte[] out = java.util.Arrays.copyOfRange(buf, pos, pos + length);
        pos += length;
        return out;
    }

    public <E extends Enum<E>> E enumValue(E[] values) throws IpcProtocolException {
        int ordinal = u8();
        if (ordinal >= values.length) {
            throw new IpcProtocolException("enum out of range: " + ordinal);
        }
        return values[ordinal];
    }

    public void expectEnd() throws IpcProtocolException {
        if (pos != end) {
            throw new IpcProtocolException("trailing bytes: " + (end - pos));
        }
    }
}
