package com.otectus.otetv.core.ipc;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads framed messages, rejecting unknown types, the wrong direction and oversized lengths before allocating.
 * Large video payloads can be streamed into a caller-owned buffer with {@link #readFully} instead of a new array.
 */
public final class IpcReader {
    public record Header(IpcType type, int payloadLength) {
    }

    private final DataInputStream in;
    private final IpcType.Direction accepted;

    public IpcReader(InputStream in, IpcType.Direction accepted) {
        this.in = in instanceof DataInputStream d ? d : new DataInputStream(in);
        this.accepted = accepted;
    }

    /** Blocks for the next header; returns null on a clean end of stream between messages. */
    public Header readHeader() throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int length = (b0 << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (length < 1) {
            throw new IpcProtocolException("bad frame length " + length);
        }
        int code = in.readUnsignedByte();
        IpcType type = IpcType.byCode(code);
        if (type == null) {
            throw new IpcProtocolException("unknown message type 0x" + Integer.toHexString(code));
        }
        if (type.direction() != accepted) {
            throw new IpcProtocolException("message " + type + " sent in the wrong direction");
        }
        int payload = length - 1;
        if (payload > type.maxPayload()) {
            throw new IpcProtocolException(type + " payload " + payload + " exceeds " + type.maxPayload());
        }
        return new Header(type, payload);
    }

    public byte[] readPayload(Header header) throws IOException {
        byte[] data = new byte[header.payloadLength()];
        in.readFully(data);
        return data;
    }

    public void readFully(byte[] dst, int off, int len) throws IOException {
        in.readFully(dst, off, len);
    }

    public void skip(long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("stream ended while skipping");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }
}
