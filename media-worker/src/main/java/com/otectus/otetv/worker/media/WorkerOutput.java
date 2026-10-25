package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.IpcWriter;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadWriter;
import com.otectus.otetv.core.ipc.PixelFormat;
import com.otectus.otetv.core.redact.Redactor;
import com.otectus.otetv.core.state.ErrorCode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Typed, thread-safe writes to the client. A write failure means the parent is gone: the worker exits. */
final class WorkerOutput {
    private final IpcWriter writer;
    private volatile boolean broken;

    WorkerOutput(OutputStream stdout) {
        this.writer = new IpcWriter(stdout);
    }

    boolean isBroken() {
        return broken;
    }

    void send(IpcType type, PayloadWriter payload) {
        if (broken) {
            return;
        }
        try {
            writer.send(type, payload);
        } catch (IOException e) {
            broken = true;
            Log.info("client pipe closed: " + e.getMessage());
            MediaWorkerMain.shutdown(0);
        }
    }

    void error(int generation, ErrorCode code, String detail) {
        Log.info("error " + code + ": " + Redactor.redact(detail));
        send(IpcType.ERROR, new Msg.Error(generation, code.ordinal(), Redactor.redact(detail)).write());
    }

    void frame(int generation, int sequence, long ptsNanos, int width, int height, byte[] pixels, int length) {
        if (broken) {
            return;
        }
        PayloadWriter header = new PayloadWriter(FrameHeader.BYTES);
        new FrameHeader(generation, sequence, ptsNanos, width, height, width * 4, PixelFormat.RGBA).write(header);
        try {
            writer.sendWithBody(IpcType.VIDEO_FRAME, header, ByteBuffer.wrap(pixels, 0, length));
        } catch (IOException e) {
            broken = true;
            MediaWorkerMain.shutdown(0);
        }
    }

    void audio(int generation, long ptsNanos, byte[] pcm, int length) {
        if (broken) {
            return;
        }
        PayloadWriter header = new Msg.AudioHeader(generation, ptsNanos, 48_000, 2, length).write();
        try {
            writer.sendWithBody(IpcType.AUDIO_CHUNK, header, ByteBuffer.wrap(pcm, 0, length));
        } catch (IOException e) {
            broken = true;
            MediaWorkerMain.shutdown(0);
        }
    }
}
