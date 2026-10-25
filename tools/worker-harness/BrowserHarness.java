import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.IpcWriter;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.state.ErrorCode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end harness for the native browser worker: launches it the way the client does, loads one official YouTube
 * embed, clicks the player like a viewer if autoplay is refused, then pauses, seeks, resumes and changes volume.
 * Prints the typed player events, frame statistics and the time reports, and saves one painted frame as PNG.
 *
 * java -cp core/build/classes/java/main tools/worker-harness/BrowserHarness.java <worker-executable> <videoId> [seconds] [png-out]
 */
public class BrowserHarness {
    static volatile int frames;
    static volatile long lastTimeMs = -1;
    static volatile int lastState = -99;
    static volatile boolean autoplayBlocked;
    static volatile boolean savedFrame;
    static volatile String error = "";
    static final List<String> events = new ArrayList<>();
    static String pngOut;
    static final int GEN = 3;

    public static void main(String[] args) throws Exception {
        String exe = args[0];
        String videoId = args[1];
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        pngOut = args.length > 3 && !args[3].isEmpty() ? args[3] : null;
        ProcessBuilder pb = new ProcessBuilder(exe, "--otetv-parent-pid=" + ProcessHandle.current().pid(),
                "--otetv-app-id=" + OteTvCore.APP_ID, "--otetv-cache=/tmp/otetv-browser-harness");
        pb.directory(new File(exe).getParentFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        IpcWriter w = new IpcWriter(p.getOutputStream());
        IpcReader r = new IpcReader(new BufferedInputStream(p.getInputStream(), 1 << 22), IpcType.Direction.TO_CLIENT);
        Thread reader = new Thread(() -> read(r));
        reader.setDaemon(true);
        reader.start();

        long t0 = System.nanoTime();
        w.send(IpcType.HELLO, new Msg.Hello(OteTvCore.IPC_PROTOCOL_VERSION, "harness", Msg.ROLE_BROWSER, false).write());
        w.send(IpcType.RESIZE, new Msg.Resize(1280, 720).write());
        w.send(IpcType.SET_VOLUME, new Msg.SetVolume(GEN, 30, true).write());
        w.send(IpcType.LOAD_VIDEO, new Msg.LoadVideo(GEN, videoId, 0, true, false).write());
        boolean clicked = false;
        boolean paused = false;
        boolean seeked = false;
        boolean resumed = false;
        while (System.nanoTime() - t0 < seconds * 1_000_000_000L) {
            Thread.sleep(250);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            w.send(IpcType.PING, new Msg.Ping(ms, System.nanoTime()).write());
            if (!clicked && (autoplayBlocked || (ms > 12_000 && lastState != 1))) {
                clicked = true;
                log("click the player (a real local gesture)");
                w.send(IpcType.MOUSE, new Msg.Mouse(GEN, Msg.Mouse.MOVE, 640, 360, 0, 0, 0).write());
                w.send(IpcType.MOUSE, new Msg.Mouse(GEN, Msg.Mouse.DOWN, 640, 360, 0, 0, 0).write());
                w.send(IpcType.MOUSE, new Msg.Mouse(GEN, Msg.Mouse.UP, 640, 360, 0, 0, 0).write());
            }
            if (lastState == 1 && !paused && lastTimeMs > 6000) {
                paused = true;
                log("pause at " + lastTimeMs + " ms");
                w.send(IpcType.PAUSE, new Msg.Gen(GEN).write());
            } else if (paused && !seeked && lastState == 2) {
                seeked = true;
                log("seek to 30000 ms while paused");
                w.send(IpcType.SEEK, new Msg.Seek(GEN, 30_000, true).write());
            } else if (seeked && !resumed && Math.abs(lastTimeMs - 30_000) < 2000) {
                resumed = true;
                log("resume");
                w.send(IpcType.SET_VOLUME, new Msg.SetVolume(GEN, 20, true).write());
                w.send(IpcType.PLAY, new Msg.Gen(GEN).write());
            }
        }
        log("summary: frames " + frames + ", last state " + lastState + ", last time " + lastTimeMs + " ms, autoplay blocked " + autoplayBlocked
                + (error.isEmpty() ? "" : ", error " + error));
        w.send(IpcType.SHUTDOWN);
        boolean exited = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        log("worker exited " + exited + (exited ? " code " + p.exitValue() : ""));
        if (!exited) {
            p.destroyForcibly();
        }
    }

    static void read(IpcReader r) {
        try {
            while (true) {
                IpcReader.Header h = r.readHeader();
                if (h == null) {
                    log("worker closed its output");
                    return;
                }
                if (h.type() == IpcType.VIDEO_FRAME) {
                    byte[] hb = new byte[FrameHeader.BYTES];
                    r.readFully(hb, 0, hb.length);
                    FrameHeader fh = FrameHeader.read(new PayloadReader(hb));
                    fh.validate(h.payloadLength(), false);
                    byte[] px = new byte[h.payloadLength() - FrameHeader.BYTES];
                    r.readFully(px, 0, px.length);
                    frames++;
                    if (frames == 1) {
                        log("first frame " + fh.width() + "x" + fh.height() + " " + fh.format());
                    }
                    if (pngOut != null && !savedFrame && lastState == 1 && lastTimeMs > 3000) {
                        savedFrame = true;
                        BufferedImage img = new BufferedImage(fh.width(), fh.height(), BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < fh.height(); y++) {
                            for (int x = 0; x < fh.width(); x++) {
                                int i = y * fh.stride() + x * 4;  // BGRA
                                img.setRGB(x, y, ((px[i + 2] & 0xFF) << 16) | ((px[i + 1] & 0xFF) << 8) | (px[i] & 0xFF));
                            }
                        }
                        ImageIO.write(img, "png", new File(pngOut));
                        log("saved a playing frame at " + lastTimeMs + " ms -> " + pngOut);
                    }
                    continue;
                }
                PayloadReader pr = new PayloadReader(r.readPayload(h));
                switch (h.type()) {
                    case HELLO_ACK -> {
                        Msg.HelloAck ack = Msg.HelloAck.read(pr);
                        log("HELLO_ACK protocol " + ack.protocol() + " worker " + ack.workerVersion() + " runtime " + ack.runtimeVersion()
                                + " sandboxed " + ((ack.flags() & Msg.HelloAck.FLAG_SANDBOXED) != 0));
                    }
                    case PLAYER_EVENT -> {
                        Msg.PlayerEvent e = Msg.PlayerEvent.read(pr);
                        switch (e.event()) {
                            case Msg.PlayerEvent.TIME -> lastTimeMs = e.value();
                            case Msg.PlayerEvent.STATE -> {
                                lastState = (int) e.value();
                                log("state " + e.value() + " at " + lastTimeMs + " ms");
                            }
                            case Msg.PlayerEvent.AUTOPLAY_BLOCKED -> {
                                autoplayBlocked = true;
                                log("autoplay blocked");
                            }
                            case Msg.PlayerEvent.ERROR -> log("player error " + e.value() + " -> " + ErrorCode.fromYouTubeError((int) e.value()));
                            default -> log("event " + e.event() + " value " + e.value() + (e.detail().isEmpty() ? "" : " " + e.detail()));
                        }
                    }
                    case ERROR -> {
                        Msg.Error e = Msg.Error.read(pr);
                        error = ErrorCode.byOrdinal(e.code()) + ": " + e.detail();
                        log("ERROR " + error);
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception e) {
            log("reader stopped: " + e);
        }
    }

    static synchronized void log(String s) {
        System.out.println(s);
    }
}
