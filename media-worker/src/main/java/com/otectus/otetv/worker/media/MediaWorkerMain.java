package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.ipc.SourceGrant;
import com.otectus.otetv.core.state.ErrorCode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OteTV's out-of-process direct-media worker (section 3). It has no Minecraft dependency, accepts only typed IPC
 * messages on stdin, writes framed messages on stdout and logs on stderr. It exits when its parent exits or closes the
 * pipe, so it can never be orphaned (section 17.3). A native crash here becomes a recoverable TV error in Minecraft.
 */
public final class MediaWorkerMain {
    private static volatile MediaSession session;
    private static volatile boolean shuttingDown;
    private static WorkerOutput out;
    private static final ClientClock CLOCK = new ClientClock();
    private static final ExecutorService OPENER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "otetv opener");
        t.setDaemon(true);
        return t;
    });
    private static boolean helloDone;

    private MediaWorkerMain() {
    }

    public static void main(String[] args) {
        OutputStream protocol = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 20);
        // Nothing but framed messages may reach stdout: any library printing goes to stderr instead.
        System.setOut(System.err);
        out = new WorkerOutput(protocol);
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--parent-pid")) {
                try {
                    long pid = Long.parseLong(args[i + 1]);
                    ProcessHandle.of(pid).ifPresentOrElse(h -> h.onExit().thenRun(() -> shutdown(0)), () -> shutdown(0));
                } catch (NumberFormatException e) {
                    Log.info("ignoring malformed parent pid");
                }
            }
        }
        IpcReader reader = new IpcReader(new BufferedInputStream(new FileInputStream(FileDescriptor.in), 64 * 1024), IpcType.Direction.TO_WORKER);
        try {
            while (!shuttingDown) {
                IpcReader.Header header = reader.readHeader();
                if (header == null) {
                    break;
                }
                byte[] payload = reader.readPayload(header);
                dispatch(header.type(), new PayloadReader(payload));
            }
        } catch (IOException e) {
            Log.info("protocol stream ended: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.warn("fatal worker error", e);
        }
        shutdown(0);
    }

    private static void dispatch(IpcType type, PayloadReader p) throws IOException {
        switch (type) {
            case HELLO -> hello(Msg.Hello.read(p));
            case OPEN -> {
                requireHello();
                SourceGrant grant = SourceGrant.read(p);
                replaceSession(grant);
            }
            case PLAY -> withSession(Msg.Gen.read(p).generation(), MediaSession::play);
            case PAUSE -> withSession(Msg.Gen.read(p).generation(), MediaSession::pause);
            case STOP, CLOSE -> {
                Msg.Gen.read(p);
                closeSession();
            }
            case SEEK -> {
                Msg.Seek s = Msg.Seek.read(p);
                MediaSession current = session;
                if (current == null || current.hasFailed()) {
                    return; // a failed session is reopened by the client, not by seeking it
                }
                if (current.isHls()) {
                    // HLS is re-opened at the segment containing the target instead of byte-seeked.
                    SourceGrant g = current.grant();
                    replaceSession(new SourceGrant(s.generation(), g.kind(), g.location(), g.captionsLocation(), g.lanGrants(),
                            g.maxWidth(), g.maxHeight(), g.maxFps(), s.positionMs(), g.audioTrack(), false, g.videoEnabled(), g.live()));
                } else {
                    current.seek(s.generation(), s.positionMs());
                }
            }
            case SET_VIDEO_ENABLED -> {
                boolean enabled = Msg.Flag.read(p).value();
                MediaSession current = session;
                if (current != null) {
                    current.setVideoEnabled(enabled);
                }
            }
            case CLOCK_REPORT -> CLOCK.report(Msg.ClockReport.read(p));
            case AUDIO_CREDIT -> {
                Msg.AudioCredit c = Msg.AudioCredit.read(p);
                MediaSession current = session;
                if (current != null) {
                    current.credit(c);
                }
            }
            case SET_RATE -> Msg.SetRate.read(p); // no pitch-preserving rate path is qualified; capability stays off
            case SELECT_TRACK -> {
                Msg.SelectTrack t = Msg.SelectTrack.read(p);
                MediaSession current = session;
                if (current != null && t.kind() == Msg.Track.AUDIO) {
                    SourceGrant g = current.grant();
                    replaceSession(new SourceGrant(t.generation(), g.kind(), g.location(), g.captionsLocation(), g.lanGrants(), g.maxWidth(),
                            g.maxHeight(), g.maxFps(), g.startPositionMs(), Math.max(0, t.index()), g.startPaused(), g.videoEnabled(), g.live()));
                }
            }
            case SET_OUTPUT -> Msg.SetOutput.read(p); // applied on the next open
            case PING -> {
                Msg.Ping ping = Msg.Ping.read(p);
                long now = System.nanoTime();
                CLOCK.noteOffset(ping.clientNanos(), now);
                out.send(IpcType.PONG, new Msg.Pong(ping.nonce(), ping.clientNanos(), now).write());
            }
            case SHUTDOWN -> shutdown(0);
            default -> Log.info("ignoring message " + type);
        }
    }

    private static void hello(Msg.Hello hello) {
        if (hello.protocol() != OteTvCore.IPC_PROTOCOL_VERSION || hello.role() != Msg.ROLE_MEDIA) {
            out.error(0, ErrorCode.RUNTIME_INCOMPATIBLE, "protocol " + hello.protocol() + " role " + hello.role());
            shutdown(2);
            return;
        }
        try {
            GStreamerRuntime.init();
        } catch (Throwable t) {
            // Missing native libraries surface as UnsatisfiedLinkError or GstException: report, then exit cleanly.
            out.error(0, ErrorCode.RUNTIME_MISSING, "GStreamer could not be loaded: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
            shutdown(3);
            return;
        }
        helloDone = true;
        String version = MediaWorkerMain.class.getPackage().getImplementationVersion();
        out.send(IpcType.HELLO_ACK, new Msg.HelloAck(OteTvCore.IPC_PROTOCOL_VERSION, version == null ? "dev" : version,
                GStreamerRuntime.version(), 0, GStreamerRuntime.availableElements()).write());
    }

    private static void requireHello() throws IOException {
        if (!helloDone) {
            throw new IOException("OPEN before HELLO");
        }
    }

    private static void withSession(int generation, java.util.function.Consumer<MediaSession> action) {
        MediaSession current = session;
        if (current != null && current.generation() == generation) {
            action.accept(current);
        }
    }

    /** Opening may block on the network; it runs off the IPC thread so heartbeats keep flowing. */
    private static synchronized void replaceSession(SourceGrant grant) {
        closeSession();
        MediaSession next = new MediaSession(out, grant, CLOCK);
        session = next;
        OPENER.submit(next::start);
    }

    private static synchronized void closeSession() {
        MediaSession current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    static void shutdown(int code) {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        try {
            closeSession();
        } catch (RuntimeException ignored) {
            // exiting anyway
        }
        Runtime.getRuntime().halt(code);
    }
}
