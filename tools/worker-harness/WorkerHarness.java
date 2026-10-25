import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.ipc.FrameHeader;
import com.otectus.otetv.core.ipc.IpcReader;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.IpcWriter;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.PayloadReader;
import com.otectus.otetv.core.ipc.PayloadWriter;
import com.otectus.otetv.core.ipc.SourceGrant;
import com.otectus.otetv.core.net.LanGrant;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-to-end harness for the media worker: launches it exactly like the client does, plays a source while simulating
 * a perfect audio device (real-time consumption, credits and clock reports), then seeks, pauses and resumes.
 * Reports frame/audio counts, measured A/V offsets at frame arrival, and writes one decoded frame as PNG.
 *
 * java -cp core/build/classes/java/main tools/worker-harness/WorkerHarness.java <worker.jar> <FILE|HTTPS|HLS> <location>
 *      [seconds] [png-out] [lan-host:port] [captions-url]
 */
public class WorkerHarness {
    static volatile int generation = 7;
    static volatile long firstAudioPts = -1;
    static volatile long audioStartNanos = -1;
    static volatile long receivedAudioBytes;
    static volatile long pausedAtPts = -1;
    static volatile boolean playing = true;
    static int frames;
    static long audioChunks;
    static final List<Long> offsets = new ArrayList<>();
    static long lastFramePts = -1;
    static String lastState = "";
    static String error = "";
    static boolean eos;
    static boolean savedFrame;
    static String pngOut;

    static long audibleNow() {
        if (firstAudioPts < 0 || audioStartNanos < 0) {
            return -1;
        }
        if (!playing) {
            return pausedAtPts;
        }
        long elapsed = System.nanoTime() - audioStartNanos;
        long consumedNanos = Math.min(elapsed, receivedAudioBytes * 1_000_000_000L / (48_000L * 4));
        return firstAudioPts + consumedNanos;
    }

    static long consumedBytes() {
        long pts = audibleNow();
        if (pts < 0) {
            return 0;
        }
        return Math.max(0, Math.min(receivedAudioBytes, (pts - firstAudioPts) * 48_000L * 4 / 1_000_000_000L));
    }

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        SourceGrant.Kind kind = SourceGrant.Kind.valueOf(args[1]);
        String location = args[2];
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        pngOut = args.length > 4 && !args[4].isEmpty() ? args[4] : null;
        String captions = args.length > 6 && !args[6].isEmpty() ? args[6] : null;
        List<LanGrant> lan = new ArrayList<>();
        if (args.length > 5 && !args[5].isEmpty()) {
            String[] hp = args[5].split(":");
            lan.add(new LanGrant(hp[0], Integer.parseInt(hp[1])));
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx384m", "-jar", jar, "--parent-pid", Long.toString(ProcessHandle.current().pid()));
        Map<String, String> env = pb.environment();
        Path registry = Files.createTempFile("otetv-gst-registry", ".bin");
        env.put("GST_REGISTRY_1_0", registry.toString());
        env.put("GST_DEBUG", "1");
        env.put("GST_PLUGIN_FEATURE_RANK", "hlsdemux:NONE,hlsdemux2:NONE,dashdemux:NONE,souphttpsrc:NONE");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        IpcWriter w = new IpcWriter(p.getOutputStream());
        IpcReader r = new IpcReader(new BufferedInputStream(p.getInputStream(), 1 << 20), IpcType.Direction.TO_CLIENT);
        w.send(IpcType.HELLO, new Msg.Hello(OteTvCore.IPC_PROTOCOL_VERSION, "harness", Msg.ROLE_MEDIA, false).write());

        Thread reader = new Thread(() -> {
            byte[] hdr = new byte[FrameHeader.BYTES];
            try {
                while (true) {
                    IpcReader.Header h = r.readHeader();
                    if (h == null) {
                        System.out.println("worker closed output");
                        return;
                    }
                    if (h.type() == IpcType.VIDEO_FRAME) {
                        r.readFully(hdr, 0, hdr.length);
                        FrameHeader fh = FrameHeader.read(new PayloadReader(hdr));
                        fh.validate(h.payloadLength(), true);
                        byte[] px = new byte[h.payloadLength() - FrameHeader.BYTES];
                        r.readFully(px, 0, px.length);
                        if (fh.generation() != generation) {
                            continue;
                        }
                        synchronized (WorkerHarness.class) {
                            if (fh.generation() != generation) {
                                continue;
                            }
                            frames++;
                            lastFramePts = fh.ptsNanos();
                            long audible = audibleNow();
                            if (audible >= 0 && playing) {
                                offsets.add((fh.ptsNanos() - audible) / 1_000_000L);
                            }
                        }
                        if (pngOut != null && !savedFrame && fh.ptsNanos() > 1_500_000_000L) {
                            savedFrame = true;
                            BufferedImage img = new BufferedImage(fh.width(), fh.height(), BufferedImage.TYPE_INT_RGB);
                            for (int y = 0; y < fh.height(); y++) {
                                for (int x = 0; x < fh.width(); x++) {
                                    int i = y * fh.stride() + x * 4;
                                    img.setRGB(x, y, ((px[i] & 0xFF) << 16) | ((px[i + 1] & 0xFF) << 8) | (px[i + 2] & 0xFF));
                                }
                            }
                            ImageIO.write(img, "png", new File(pngOut));
                            System.out.println("saved frame " + fh.width() + "x" + fh.height() + " pts " + fh.ptsNanos() / 1_000_000 + " ms -> " + pngOut);
                        }
                        continue;
                    }
                    byte[] payload = r.readPayload(h);
                    PayloadReader pr = new PayloadReader(payload);
                    switch (h.type()) {
                        case HELLO_ACK -> {
                            Msg.HelloAck ack = Msg.HelloAck.read(pr);
                            System.out.println("HELLO_ACK protocol " + ack.protocol() + " runtime " + ack.runtimeVersion() + " elements " + ack.elements());
                        }
                        case AUDIO_CHUNK -> {
                            Msg.AudioHeader ah = Msg.AudioHeader.read(pr);
                            if (ah.generation() != generation) {
                                continue;
                            }
                            synchronized (WorkerHarness.class) {
                                if (ah.generation() != generation) {
                                    continue; // the generation changed while this chunk was being read
                                }
                                if (firstAudioPts < 0) {
                                    System.out.println("first audio chunk gen " + ah.generation() + " pts " + ah.ptsNanos() / 1_000_000 + " ms");
                                    firstAudioPts = ah.ptsNanos();
                                    audioStartNanos = System.nanoTime() + 300_000_000L; // simulated device prebuffer latency
                                }
                                receivedAudioBytes += ah.byteLength();
                                audioChunks++;
                            }
                        }
                        case STATE -> {
                            Msg.State s = Msg.State.read(pr);
                            String line = LocalPlaybackState.byOrdinal(s.state()) + " pos=" + s.positionMs() + " dur=" + s.durationMs()
                                    + " flags=" + s.flags() + " video=" + s.videoWidth() + "x" + s.videoHeight();
                            if (!line.equals(lastState)) {
                                lastState = line;
                                System.out.println("STATE gen " + s.generation() + " " + line);
                            }
                        }
                        case CAPS -> {
                            Msg.Caps c = Msg.Caps.read(pr);
                            System.out.println("CAPS " + com.otectus.otetv.core.capability.PlaybackCapabilities.fromBits(c.bits(), c.durationMs()));
                        }
                        case ERROR -> {
                            Msg.Error e = Msg.Error.read(pr);
                            error = ErrorCode.byOrdinal(e.code()) + ": " + e.detail();
                            System.out.println("ERROR " + error);
                        }
                        case EOS -> {
                            eos = true;
                            long audioEnd = firstAudioPts < 0 ? -1 : (firstAudioPts + receivedAudioBytes * 1_000_000_000L / (48_000L * 4)) / 1_000_000;
                            System.out.println("EOS (audible " + audibleNow() / 1_000_000 + " ms, audio received up to " + audioEnd + " ms, last frame "
                                    + lastFramePts / 1_000_000 + " ms)");
                        }
                        case CUE -> {
                            Msg.Cue c = Msg.Cue.read(pr);
                            System.out.println("CUE " + c.startMs() + "-" + c.endMs() + " " + c.text().replace('\n', '|'));
                        }
                        default -> {
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("reader stopped: " + e);
            }
        });
        reader.setDaemon(true);
        reader.start();

        Thread.sleep(800);
        SourceGrant grant = new SourceGrant(generation, kind, location, captions, lan, 640, 360, 30, 0, 0, false, true, false);
        PayloadWriter pw = new PayloadWriter();
        grant.write(pw);
        w.send(IpcType.OPEN, pw);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long seekAt = System.nanoTime() + (seconds / 2) * 1_000_000_000L;
        boolean seeked = false;
        boolean pausedOnce = false;
        while (System.nanoTime() < end) {
            Thread.sleep(50);
            long now = System.nanoTime();
            int gen = generation;
            w.send(IpcType.AUDIO_CREDIT, new Msg.AudioCredit(gen, consumedBytes()).write());
            long audible = audibleNow();
            w.send(IpcType.CLOCK_REPORT, new Msg.ClockReport(gen, now, audible, (audible >= 0 ? Msg.CLOCK_AUDIO_ACTIVE : 0) | (playing ? Msg.CLOCK_PLAYING : 0)).write());
            w.send(IpcType.PING, new Msg.Ping(now, now).write());
            if (!seeked && now >= seekAt) {
                seeked = true;
                synchronized (WorkerHarness.class) {
                    System.out.println("--- frames before seek: " + frames + ", audio chunks " + audioChunks + ", last frame pts " + lastFramePts / 1_000_000 + " ms");
                    printOffsets();
                    generation = gen + 1;
                    firstAudioPts = -1;
                    audioStartNanos = -1;
                    receivedAudioBytes = 0;
                    frames = 0;
                    offsets.clear();
                }
                w.send(IpcType.SEEK, new Msg.Seek(generation, 4_000, true).write());
                System.out.println("--- seek to 4000 ms (generation " + generation + ")");
            }
            if (seeked && !pausedOnce && now >= seekAt + 1_500_000_000L) {
                pausedOnce = true;
                pausedAtPts = audibleNow();
                playing = false;
                w.send(IpcType.PAUSE, new Msg.Gen(generation).write());
                int before = frames;
                // A real client keeps reporting its (now frozen) clock while paused.
                for (int i = 0; i < 14; i++) {
                    Thread.sleep(50);
                    long t = System.nanoTime();
                    w.send(IpcType.CLOCK_REPORT, new Msg.ClockReport(generation, t, pausedAtPts, pausedAtPts >= 0 ? Msg.CLOCK_AUDIO_ACTIVE : 0).write());
                    w.send(IpcType.PING, new Msg.Ping(t, t).write());
                }
                System.out.println("--- paused 700 ms: frames during pause " + (frames - before));
                playing = true;
                audioStartNanos = System.nanoTime() - (pausedAtPts - firstAudioPts);
                w.send(IpcType.PLAY, new Msg.Gen(generation).write());
            }
        }
        synchronized (WorkerHarness.class) {
            System.out.println("--- after seek: frames " + frames + ", last frame pts " + lastFramePts / 1_000_000 + " ms, eos " + eos);
            printOffsets();
        }
        w.send(IpcType.SHUTDOWN);
        p.waitFor();
        System.out.println("worker exit code " + p.exitValue() + (error.isEmpty() ? "" : ", error " + error));
        Files.deleteIfExists(registry);
    }

    static void printOffsets() {
        if (offsets.isEmpty()) {
            System.out.println("    A/V offset: no samples");
            return;
        }
        List<Long> sorted = new ArrayList<>(offsets);
        sorted.sort(Long::compare);
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
        long p05 = sorted.get((int) (sorted.size() * 0.05));
        System.out.println("    A/V offset at arrival (frame pts - audible pts): p05 " + p05 + " ms, p50 " + p50 + " ms, p95 " + p95 + " ms, n=" + sorted.size());
    }
}
