package com.otectus.otetv.core.ipc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Thread-safe frame writer. Video frames are written as header + pixels without an intermediate copy. */
public final class IpcWriter {
    private final OutputStream out;
    private final byte[] chunk = new byte[64 * 1024];

    public IpcWriter(OutputStream out) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out, 256 * 1024);
    }

    public synchronized void send(IpcType type, PayloadWriter payload) throws IOException {
        out.write(payload.toFrame(type));
        out.flush();
    }

    public void send(IpcType type) throws IOException {
        send(type, new PayloadWriter(0));
    }

    /** Writes {@code [len][type][header][pixels]}; the pixel buffer is read from its position to its limit. */
    public synchronized void sendWithBody(IpcType type, PayloadWriter header, ByteBuffer body) throws IOException {
        int bodyLength = body.remaining();
        long payload = (long) header.size() + bodyLength;
        if (payload > type.maxPayload()) {
            throw new IpcProtocolException(type + " payload " + payload + " exceeds " + type.maxPayload());
        }
        int length = (int) payload + 1;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type.code());
        byte[] h = header.toByteArray();
        out.write(h);
        if (body.hasArray()) {
            out.write(body.array(), body.arrayOffset() + body.position(), bodyLength);
            body.position(body.limit());
        } else {
            while (body.hasRemaining()) {
                int n = Math.min(chunk.length, body.remaining());
                body.get(chunk, 0, n);
                out.write(chunk, 0, n);
            }
        }
        out.flush();
    }

    public synchronized void close() throws IOException {
        out.close();
    }
}
