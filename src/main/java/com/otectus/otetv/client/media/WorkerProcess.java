package com.otectus.otetv.client.media;

import com.otectus.otetv.OteTV;
import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.IpcWriter;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.ipc.PayloadWriter;
import com.otectus.otetv.core.redact.Redactor;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One supervised worker process (section 8.1 RuntimeSupervisor): launched from an argument array with verified
 * absolute paths, framed protocol on stdin/stdout, logs on stderr, heartbeat, and teardown that kills only this
 * tracked process. A worker that closes its pipe, stops answering pings or sends a malformed frame is terminated
 * and reported as a recoverable error; Minecraft never crashes because of it (section 16.3).
 */
public final class WorkerProcess implements AutoCloseable {
    public static final long HEARTBEAT_TIMEOUT_NANOS = 6_000_000_000L;
    private static final int STDERR_LINES = 200;

    public interface Listener {
        /** Non-frame message, on the reader thread. */
        void onMessage(IpcType type, PayloadReader payload) throws IOException;

        /** Video frame; the listener must consume exactly {@code pixelBytes} from {@code reader}. */
        void onVideoFrame(FrameHeader header, IpcReader reader, int pixelBytes) throws IOException;

        void onExit(int exitCode, boolean requested, String reason);
    }

    private final String name;
    private final Process process;
    private final IpcWriter writer;
    private final Listener listener;
    private final boolean directMedia;
    private final Deque<String> stderr = new ArrayDeque<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean exited = new AtomicBoolean();
    private volatile long lastPongNanos = System.nanoTime();
    private volatile long lastPingNanos;
    private volatile String exitReason = "";

    private WorkerProcess(String name, Process process, Listener listener, boolean directMedia) {
        this.name = name;
        this.process = process;
        this.listener = listener;
        this.directMedia = directMedia;
        this.writer = new IpcWriter(process.getOutputStream());
    }

    public static WorkerProcess launch(String name, List<String> command, Map<String, String> environment, Path workDir,
                                       Listener listener, boolean directMedia) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.putAll(environment);
        Process process = pb.start();
        WorkerProcess worker = new WorkerProcess(name, process, listener, directMedia);
        worker.startThreads();
        return worker;
    }

    private void startThreads() {
        Thread reader = new Thread(this::readLoop, "OteTV " + name + " reader");
        reader.setDaemon(true);
        reader.start();
        Thread err = new Thread(this::stderrLoop, "OteTV " + name + " log");
        err.setDaemon(true);
        err.start();
    }

    private void readLoop() {
        IpcReader reader = new IpcReader(new BufferedInputStream(process.getInputStream(), 256 * 1024), IpcType.Direction.TO_CLIENT);
        String reason = "worker exited";
        try {
            byte[] headerBytes = new byte[FrameHeader.BYTES];
            while (true) {
                IpcReader.Header header = reader.readHeader();
                if (header == null) {
                    reason = "worker closed its output";
                    break;
                }
                if (header.type() == IpcType.VIDEO_FRAME) {
                    if (header.payloadLength() < FrameHeader.BYTES) {
                        throw new com.otectus.otetv.core.ipc.IpcProtocolException("short frame");
                    }
                    reader.readFully(headerBytes, 0, FrameHeader.BYTES);
                    FrameHeader fh = FrameHeader.read(new PayloadReader(headerBytes));
                    fh.validate(header.payloadLength(), directMedia);
                    listener.onVideoFrame(fh, reader, header.payloadLength() - FrameHeader.BYTES);
                    continue;
                }
                byte[] payload = reader.readPayload(header);
                if (header.type() == IpcType.PONG) {
                    lastPongNanos = System.nanoTime();
                }
                listener.onMessage(header.type(), new PayloadReader(payload));
            }
        } catch (IOException e) {
            reason = closing.get() ? "closed" : Redactor.redact("protocol error: " + e.getMessage());
        } catch (RuntimeException e) {
            reason = Redactor.redact("worker handler failure: " + e);
            OteTV.LOGGER.warn("OteTV {} reader failed", name, e);
        }
        finish(reason);
    }

    private void stderrLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String clean = Redactor.redact(line.length() > 1000 ? line.substring(0, 1000) : line);
                synchronized (stderr) {
                    stderr.addLast(clean);
                    while (stderr.size() > STDERR_LINES) {
                        stderr.removeFirst();
                    }
                }
                if (com.otectus.otetv.config.OteTvClientConfig.DEBUG_LOGGING.get()) {
                    OteTV.LOGGER.info("[{}] {}", name, clean);
                }
            }
        } catch (IOException | IllegalStateException ignored) {
            // stream closed
        }
    }

    private void finish(String reason) {
        if (!exited.compareAndSet(false, true)) {
            return;
        }
        exitReason = reason;
        int code = -1;
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                code = process.exitValue();
            } else {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        listener.onExit(code, closing.get(), reason);
    }

    public boolean send(IpcType type, PayloadWriter payload) {
        if (exited.get()) {
            return false;
        }
        try {
            writer.send(type, payload);
            return true;
        } catch (IOException e) {
            if (!closing.get()) {
                OteTV.LOGGER.debug("OteTV {} pipe closed: {}", name, e.getMessage());
            }
            return false;
        }
    }

    public boolean send(IpcType type) {
        return send(type, new PayloadWriter(0));
    }

    /** Sends a ping once a second; returns false if the worker has stopped answering. */
    public boolean heartbeat() {
        long now = System.nanoTime();
        if (now - lastPingNanos >= 1_000_000_000L) {
            lastPingNanos = now;
            send(IpcType.PING, new PayloadWriter().i64(now).i64(now));
        }
        if (now - lastPongNanos > HEARTBEAT_TIMEOUT_NANOS && !exited.get()) {
            OteTV.LOGGER.warn("OteTV {} stopped responding; terminating it", name);
            process.destroyForcibly();
            return false;
        }
        return !exited.get();
    }

    public boolean isAlive() {
        return !exited.get() && process.isAlive();
    }

    public String exitReason() {
        return exitReason;
    }

    public long pid() {
        return process.pid();
    }

    public List<String> recentLog() {
        synchronized (stderr) {
            return new ArrayList<>(stderr);
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        send(IpcType.SHUTDOWN);
        try {
            writer.close();
        } catch (IOException ignored) {
            // already closed
        }
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }, "OteTV " + name + " teardown");
        killer.setDaemon(true);
        killer.start();
    }
}
