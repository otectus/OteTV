package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.captions.CaptionParser;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.hls.HlsException;
import com.otectus.otetv.core.ipc.IpcType;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.ipc.SourceGrant;
import com.otectus.otetv.core.media.MediaSniffer;
import com.otectus.otetv.core.net.CancellationToken;
import com.otectus.otetv.core.net.DestinationBlockedException;
import com.otectus.otetv.core.net.DestinationPolicy;
import com.otectus.otetv.core.net.HttpResponse;
import com.otectus.otetv.core.net.SafeHttpClient;
import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Fraction;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.elements.DecodeBin;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;

import java.io.Closeable;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One opened source: appsrc -> decodebin -> RGBA video / 48 kHz S16 stereo audio / text appsinks (section 11.1).
 * Decoding is paced by the client's audio consumption (credit window); frames are released against the client's
 * audible timestamp, so lip sync follows what is heard. Media without audio is paced by the pipeline clock. Every
 * output carries the flush generation; anything from an older generation is dropped.
 */
final class MediaSession implements Closeable {
    private static final long AUDIO_WINDOW_BYTES = 48_000L * 4;
    private static final long FRAME_LEAD_NANOS = 25_000_000L;
    private static final long STALE_NANOS = 120_000_000L;
    private static final int READ_CHUNK = 64 * 1024;
    private static final Set<String> REFUSED_FACTORIES = Set.of("hlsdemux", "hlsdemux2", "dashdemux", "dashdemux2", "mssdemux", "mssdemux2",
            "souphttpsrc", "curlhttpsrc", "rtspsrc", "rtmpsrc", "rtmp2src", "giosrc");
    /** Typefind results that need a demuxer rather than a decoder; a missing one is a container problem, not a codec one. */
    private static final Set<String> CONTAINER_CAPS = Set.of("video/webm", "audio/webm", "video/x-matroska", "audio/x-matroska",
            "video/quicktime", "audio/x-m4a", "video/x-msvideo", "video/x-flv", "video/mpegts", "application/ogg", "audio/ogg",
            "video/ogg", "application/x-id3", "application/x-apetag", "audio/x-wav", "audio/x-aiff");

    private final WorkerOutput out;
    private final SourceGrant grant;
    private final ClientClock clock;
    private final CancellationToken token = new CancellationToken();
    private final FrameQueue frames = new FrameQueue(48);
    private final Object creditLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger();
    /** Set when a flushing seek is issued; cleared by the FLUSH_STOP event reaching each sink. Samples are dropped meanwhile. */
    private final java.util.concurrent.atomic.AtomicBoolean videoFlushPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean audioFlushPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean textFlushPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();
    private ScheduledExecutorService reporter;
    private volatile int generation;
    private volatile boolean closed;
    private ByteSource source;
    private Pipeline pipeline;
    private AppSrc src;
    private volatile AppSink vsink;
    private volatile AppSink asink;
    private volatile AppSink tsink;
    private volatile boolean hasAudio;
    private volatile boolean hasVideo;
    private volatile boolean hasText;
    private volatile boolean prerolled;
    private volatile boolean ended;
    /** The pipeline reported EOS; it is forwarded only once every decoded sample has reached the client (no clipped tail). */
    private volatile boolean pipelineEos;
    private volatile boolean audioDrained;
    private volatile boolean videoDrained;
    private final Object finishLock = new Object();
    private volatile boolean failed;
    private volatile boolean playing;
    private volatile boolean videoEnabled = true;
    private volatile boolean live;
    private volatile boolean seekable;
    private volatile boolean hasSidecar;
    private volatile long durationMs = -1;
    private volatile long minPtsNanos = Long.MIN_VALUE;
    private volatile long pendingSeekMs = -1;
    /** Where playback was last placed (open or seek): the reported position until a frame or clock report says more. */
    private volatile long placedAtMs;
    private volatile boolean posterPending = true;
    private volatile long sentAudioBytes;
    private volatile long consumedAudioBytes;
    private volatile long lastFramePts = -1;
    private volatile long lastAudioPts = -1;
    private volatile long lastOutputNanos = System.nanoTime();
    private volatile int outWidth;
    private volatile int outHeight;
    private volatile String unsupportedCaps;
    private int audioPadsSeen;
    private boolean ptsBaseKnown;
    private long ptsRebase;
    private long lastTranslated = -1;
    private byte[] sniffed;
    private int sniffedLength;

    MediaSession(WorkerOutput out, SourceGrant grant, ClientClock clock) {
        this.out = out;
        this.grant = grant;
        this.clock = clock;
        this.generation = grant.generation();
        this.playing = !grant.startPaused();
        this.videoEnabled = grant.videoEnabled();
        this.placedAtMs = Math.max(0, grant.startPositionMs());
        clock.reset(generation);
    }

    boolean isHls() {
        return grant.kind() == SourceGrant.Kind.HLS;
    }

    boolean hasFailed() {
        return failed;
    }

    SourceGrant grant() {
        return grant;
    }

    // ---- opening ------------------------------------------------------------------------------------------------------

    void start() {
        try {
            openSource();
            if (closed) {
                return;
            }
            sniff();
            buildPipeline();
            startReporter();
            if (grant.captionsLocation() != null) {
                Thread t = new Thread(this::loadSidecar, "otetv captions");
                t.setDaemon(true);
                t.start();
            }
        } catch (DestinationBlockedException e) {
            fail(ErrorCode.POLICY_BLOCKED_HOST, e.describe() + " for " + e.host());
        } catch (UnknownHostException e) {
            fail(ErrorCode.NETWORK, "host not found");
        } catch (HttpByteSource.NotMediaException e) {
            fail(ErrorCode.NOT_MEDIA, e.getMessage());
        } catch (HlsByteSource.HlsRejected e) {
            fail(e.reason == HlsException.Reason.ENCRYPTED ? ErrorCode.HLS_ENCRYPTED : ErrorCode.HLS_UNSUPPORTED, e.getMessage());
        } catch (UrlRejectedException e) {
            fail(ErrorCode.POLICY_BLOCKED_HOST, "rejected link: " + e.reason());
        } catch (IOException e) {
            fail(grant.kind() == SourceGrant.Kind.FILE ? ErrorCode.MEDIA_MALFORMED : ErrorCode.NETWORK, e.getMessage());
        } catch (RuntimeException e) {
            Log.warn("pipeline setup failed", e);
            fail(ErrorCode.INTERNAL, e.getClass().getSimpleName());
        }
    }

    private SafeHttpClient httpClient() {
        return SafeHttpClient.create(new DestinationPolicy(grant.lanGrants()), "OteTV-media/1");
    }

    private void openSource() throws IOException, DestinationBlockedException, UrlRejectedException {
        switch (grant.kind()) {
            case FILE -> source = new FileByteSource(grant.location());
            case HTTPS -> source = new HttpByteSource(httpClient(), SafeUrl.parse(grant.location()), token);
            case HLS -> source = new HlsByteSource(httpClient(), SafeUrl.parse(grant.location()), token, grant.maxHeight(), grant.startPositionMs());
        }
        seekable = source.seekable() && source.size() > 0;
        live = source.isLive();
        durationMs = source.knownDurationMs();
        long start = grant.startPositionMs();
        if (start > 0) {
            if (seekable) {
                pendingSeekMs = start;
            } else if (!live) {
                // No random access: decode from the beginning (HLS: from the segment) and drop output until the target.
                minPtsNanos = start * 1_000_000L - STALE_NANOS;
            }
        }
    }

    private void sniff() throws IOException {
        byte[] head = new byte[MediaSniffer.SNIFF_BYTES];
        int n = 0;
        while (n < head.length) {
            int r = source.read(head, n, head.length - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        MediaSniffer.Kind kind = MediaSniffer.sniff(head, n);
        if (kind == MediaSniffer.Kind.HTML) {
            throw new HttpByteSource.NotMediaException("the link returned a web page, not a video file");
        }
        if (kind == MediaSniffer.Kind.HLS_PLAYLIST && grant.kind() != SourceGrant.Kind.HLS) {
            throw new HlsByteSource.HlsRejected(HlsException.Reason.UNSUPPORTED, "this link is an HLS playlist; add it as a stream");
        }
        if (n == 0) {
            throw new IOException("empty media");
        }
        if (seekable) {
            source.seek(0);
        } else {
            sniffed = head;
            sniffedLength = n;
        }
    }

    // ---- pipeline -----------------------------------------------------------------------------------------------------

    private void buildPipeline() {
        pipeline = new Pipeline("otetv-" + generation);
        src = (AppSrc) ElementFactory.make("appsrc", "src");
        DecodeBin dec = (DecodeBin) ElementFactory.make("decodebin", "dec");
        src.setStreamType(seekable ? AppSrc.StreamType.RANDOM_ACCESS : AppSrc.StreamType.STREAM);
        src.setSize(seekable ? source.size() : -1);
        src.setMaxBytes(4L * 1024 * 1024);
        src.set("block", !seekable);
        if (seekable) {
            src.connect((AppSrc.NEED_DATA) this::feedRandomAccess);
            src.connect((AppSrc.SEEK_DATA) (elem, offset) -> {
                try {
                    source.seek(offset);
                    return true;
                } catch (IOException e) {
                    Log.warn("source seek failed", e);
                    return false;
                }
            });
        }
        dec.connect((DecodeBin.AUTOPLUG_SELECT) (bin, pad, caps, factory) ->
                REFUSED_FACTORIES.contains(factory.getName()) ? DecodeBin.AutoplugSelectResult.SKIP : DecodeBin.AutoplugSelectResult.TRY);
        dec.connect((DecodeBin.UNKNOWN_TYPE) (bin, pad, caps) -> {
            String type = caps == null || caps.size() == 0 ? "unknown" : caps.getStructure(0).getName();
            unsupportedCaps = type;
            Log.info("no decoder for " + type);
        });
        dec.connect((Element.PAD_ADDED) (element, pad) -> onPad(pad));
        dec.connect((Element.NO_MORE_PADS) element -> onNoMorePads());
        pipeline.addMany(src, dec);
        if (!src.link(dec)) {
            throw new IllegalStateException("could not link appsrc to decodebin");
        }
        Bus bus = pipeline.getBus();
        bus.connect((Bus.ERROR) (srcObj, code, message) -> onError(message));
        bus.connect((Bus.EOS) srcObj -> onEos());
        bus.connect((Bus.ASYNC_DONE) srcObj -> onAsyncDone());
        if (!seekable) {
            Thread feeder = thread("feeder", this::feedStream);
            feeder.start();
        }
        pipeline.pause();
    }

    private Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, "otetv " + name);
        t.setDaemon(true);
        threads.add(t);
        return t;
    }

    /** Random-access mode: appsrc asks for {@code size} bytes at the current offset; answer exactly or end the stream. */
    private void feedRandomAccess(AppSrc elem, int size) {
        if (closed) {
            return;
        }
        int want = size <= 0 ? READ_CHUNK : Math.min(size, 4 * 1024 * 1024);
        byte[] data = new byte[want];
        int n = 0;
        try {
            while (n < want) {
                int r = source.read(data, n, want - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
        } catch (IOException e) {
            if (!closed) {
                fail(grant.kind() == SourceGrant.Kind.FILE ? ErrorCode.MEDIA_MALFORMED : ErrorCode.NETWORK, e.getMessage());
            }
            elem.endOfStream();
            return;
        }
        if (n <= 0) {
            elem.endOfStream();
            return;
        }
        push(elem, data, n);
    }

    /** Stream mode: a feeder thread pushes continuously; appsrc blocks when its queue is full. */
    private void feedStream() {
        try {
            if (sniffed != null) {
                push(src, sniffed, sniffedLength);
                sniffed = null;
            }
            byte[] data = new byte[READ_CHUNK];
            while (!closed) {
                int n = source.read(data, 0, data.length);
                if (n < 0) {
                    src.endOfStream();
                    return;
                }
                if (n > 0) {
                    push(src, data, n);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                fail(ErrorCode.NETWORK, e.getMessage());
                src.endOfStream();
            }
        }
    }

    private static void push(AppSrc elem, byte[] data, int length) {
        Buffer buffer = new Buffer(length);
        ByteBuffer bb = buffer.map(true);
        bb.put(data, 0, length);
        buffer.unmap();
        elem.pushBuffer(buffer);
    }

    private synchronized void onPad(Pad pad) {
        Caps caps = pad.getCurrentCaps();
        if (caps == null) {
            caps = pad.queryCaps(null);
        }
        if (caps == null || caps.size() == 0) {
            linkFake(pad);
            return;
        }
        Structure s = caps.getStructure(0);
        String name = s.getName();
        try {
            if (name.startsWith("video/x-raw") && vsink == null) {
                linkVideo(pad, s);
            } else if (name.startsWith("audio/x-raw") && asink == null && audioPadsSeen++ == grant.audioTrack()) {
                linkAudio(pad);
            } else if (name.startsWith("text/x-raw") && tsink == null) {
                linkText(pad);
            } else {
                linkFake(pad);
            }
        } catch (RuntimeException e) {
            Log.warn("could not link " + name, e);
            linkFake(pad);
        }
    }

    private void linkVideo(Pad pad, Structure s) {
        int srcW = s.getInteger("width");
        int srcH = s.getInteger("height");
        double par = 1.0;
        try {
            Fraction f = s.getFraction("pixel-aspect-ratio");
            if (f != null && f.getDenominator() > 0 && f.getNumerator() > 0) {
                par = f.toDouble();
            }
        } catch (RuntimeException ignored) {
            // field absent: square pixels
        }
        double aspect = srcW * par / Math.max(1, srcH);
        int maxW = Math.min(grant.maxWidth(), 1920);
        int maxH = Math.min(grant.maxHeight(), 1080);
        int w = Math.min(maxW, srcW);
        int h = (int) Math.round(w / aspect);
        if (h > maxH) {
            h = Math.min(maxH, srcH);
            w = (int) Math.round(h * aspect);
        }
        w = Math.max(16, w & ~1);
        h = Math.max(16, h & ~1);
        outWidth = w;
        outHeight = h;
        Element queue = ElementFactory.make("queue", "vqueue");
        queue.set("max-size-buffers", 6);
        queue.set("max-size-time", 0L);
        queue.set("max-size-bytes", 0);
        Element convert = ElementFactory.make("videoconvert", "vconvert");
        Element scale = ElementFactory.make("videoscale", "vscale");
        Element filter = ElementFactory.make("capsfilter", "vcaps");
        filter.set("caps", new Caps("video/x-raw,format=RGBA,width=" + w + ",height=" + h + ",pixel-aspect-ratio=1/1"));
        AppSink sink = (AppSink) ElementFactory.make("appsink", "vsink");
        sink.set("emit-signals", false);
        sink.set("sync", false);
        sink.set("max-buffers", 4);
        sink.set("drop", false);
        sink.set("enable-last-sample", false);
        pipeline.addMany(queue, convert, scale, filter, sink);
        Element.linkMany(queue, convert, scale, filter, sink);
        for (Element e : new Element[]{queue, convert, scale, filter, sink}) {
            e.syncStateWithParent();
        }
        try {
            pad.link(queue.getStaticPad("sink"));
        } catch (Exception e) {
            throw new IllegalStateException("video link failed");
        }
        watchFlush(sink, videoFlushPending);
        frames.limitForFrameBytes(w * h * 4);
        vsink = sink;
        hasVideo = true;
        thread("video", this::pullVideo).start();
        thread("scheduler", this::schedule).start();
        Log.info("video " + srcW + "x" + srcH + " -> " + w + "x" + h);
    }

    private void linkAudio(Pad pad) {
        Element queue = ElementFactory.make("queue", "aqueue");
        Element convert = ElementFactory.make("audioconvert", "aconvert");
        Element resample = ElementFactory.make("audioresample", "aresample");
        Element filter = ElementFactory.make("capsfilter", "acaps");
        filter.set("caps", new Caps("audio/x-raw,format=S16LE,rate=48000,channels=2,layout=interleaved"));
        AppSink sink = (AppSink) ElementFactory.make("appsink", "asink");
        sink.set("emit-signals", false);
        sink.set("sync", false);
        sink.set("max-buffers", 8);
        sink.set("drop", false);
        sink.set("enable-last-sample", false);
        pipeline.addMany(queue, convert, resample, filter, sink);
        Element.linkMany(queue, convert, resample, filter, sink);
        for (Element e : new Element[]{queue, convert, resample, filter, sink}) {
            e.syncStateWithParent();
        }
        try {
            pad.link(queue.getStaticPad("sink"));
        } catch (Exception e) {
            throw new IllegalStateException("audio link failed");
        }
        watchFlush(sink, audioFlushPending);
        asink = sink;
        hasAudio = true;
        thread("audio", this::pullAudio).start();
    }

    private void linkText(Pad pad) {
        Element queue = ElementFactory.make("queue", "tqueue");
        AppSink sink = (AppSink) ElementFactory.make("appsink", "tsink");
        sink.set("emit-signals", false);
        sink.set("sync", false);
        sink.set("max-buffers", 32);
        sink.set("drop", true);
        pipeline.addMany(queue, sink);
        queue.link(sink);
        queue.syncStateWithParent();
        sink.syncStateWithParent();
        try {
            pad.link(queue.getStaticPad("sink"));
        } catch (Exception e) {
            return;
        }
        watchFlush(sink, textFlushPending);
        tsink = sink;
        hasText = true;
        thread("text", this::pullText).start();
    }

    /** Clears a sink's flush barrier when the FLUSH_STOP of a seek reaches it; later samples belong to the new segment. */
    private static void watchFlush(AppSink sink, java.util.concurrent.atomic.AtomicBoolean pending) {
        Pad pad = sink.getStaticPad("sink");
        pad.addProbe(org.freedesktop.gstreamer.PadProbeType.EVENT_FLUSH, (p, info) -> {
            org.freedesktop.gstreamer.event.Event e = info.getEvent();
            Structure st = e == null ? null : e.getStructure();
            if (st != null && "GstEventFlushStop".equals(st.getName())) {
                pending.set(false);
            }
            return org.freedesktop.gstreamer.PadProbeReturn.OK;
        });
    }

    private void linkFake(Pad pad) {
        Element fake = ElementFactory.make("fakesink", null);
        fake.set("sync", false);
        pipeline.add(fake);
        fake.syncStateWithParent();
        try {
            pad.link(fake.getStaticPad("sink"));
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    private void onNoMorePads() {
        if (closed || failed) {
            return;
        }
        if (!hasAudio && vsink != null) {
            // Silent video is paced by the pipeline clock instead of audio credits.
            vsink.set("sync", true);
        }
        if (!hasAudio && !hasVideo) {
            if (unsupportedCaps != null) {
                failMissingElement();
            } else {
                fail(ErrorCode.UNSUPPORTED_CONTAINER, "no playable stream");
            }
            return;
        }
        if (unsupportedCaps != null) {
            Log.info("playing without unsupported stream " + unsupportedCaps);
        }
        sendCaps();
    }

    private void onAsyncDone() {
        if (closed || failed) {
            return;
        }
        if (durationMs < 0 && !live) {
            long d = pipeline.queryDuration(TimeUnit.MILLISECONDS);
            if (d > 0) {
                durationMs = d;
            }
        }
        boolean first = !prerolled;
        prerolled = true;
        if (first) {
            sendCaps();
        }
        long pending = pendingSeekMs;
        if (pending >= 0) {
            pendingSeekMs = -1;
            doSeek(pending);
            return;
        }
        applyPlayState();
    }

    private void applyPlayState() {
        if (pipeline == null) {
            return;
        }
        if (playing) {
            pipeline.play();
        } else {
            pipeline.pause();
        }
    }

    private void onEos() {
        if (closed || failed) {
            return;
        }
        pipelineEos = true;
        maybeFinish();
    }

    /**
     * Sends EOS once the pipeline has ended and both sinks are drained: the appsinks still hold up to a credit window of
     * audio when the bus reports EOS, and announcing the end early would clip the tail (a trusted viewer's ENDED report
     * advances the shared queue). Repeated EOS messages, e.g. when PLAY is pressed after the end, are ignored.
     */
    private void maybeFinish() {
        synchronized (finishLock) {
            if (ended || !pipelineEos || closed || failed) {
                return;
            }
            if (asink != null && !audioDrained) {
                return;
            }
            if (vsink != null && (!videoDrained || frames.size() > 0)) {
                return;
            }
            ended = true;
        }
        out.send(IpcType.EOS, new Msg.Gen(generation).write());
        sendState();
    }

    private void failMissingElement() {
        String caps = unsupportedCaps;
        if (CONTAINER_CAPS.contains(caps)) {
            fail(ErrorCode.UNSUPPORTED_CONTAINER, "no demuxer for " + caps);
        } else {
            fail(ErrorCode.UNSUPPORTED_CODEC, "no decoder for " + caps);
        }
    }

    private void onError(String message) {
        if (closed || failed) {
            return;
        }
        String m = message == null ? "" : message;
        String lower = m.toLowerCase(Locale.ROOT);
        if (unsupportedCaps != null) {
            failMissingElement();
        } else if (lower.contains("could not determine type") || lower.contains("not-negotiated") && !hasVideo && !hasAudio) {
            fail(ErrorCode.UNSUPPORTED_CONTAINER, m);
        } else if (lower.contains("missing") && lower.contains("plug")) {
            fail(ErrorCode.UNSUPPORTED_CODEC, m);
        } else {
            fail(ErrorCode.MEDIA_MALFORMED, m);
        }
    }

    private void fail(ErrorCode code, String detail) {
        if (failed) {
            return;
        }
        failed = true;
        out.error(generation, code, detail == null ? "" : detail);
        sendState();
    }

    // ---- timestamps ----------------------------------------------------------------------------------------------------

    /**
     * Converts buffer timestamps to media position. Files already carry media timestamps; HLS transport streams are
     * rebased to the playlist timeline and re-anchored across discontinuities so time stays continuous.
     */
    private synchronized long translate(long pts) {
        if (pts < 0) {
            return lastTranslated < 0 ? 0 : lastTranslated + 33_000_000L;
        }
        if (grant.kind() != SourceGrant.Kind.HLS) {
            lastTranslated = pts;
            return pts;
        }
        if (!ptsBaseKnown) {
            ptsBaseKnown = true;
            ptsRebase = pts - source.timelineOffsetNanos();
        }
        long t = pts - ptsRebase;
        if (lastTranslated >= 0 && Math.abs(t - lastTranslated) > 3_000_000_000L) {
            ptsRebase = pts - (lastTranslated + 40_000_000L);
            t = pts - ptsRebase;
        }
        lastTranslated = Math.max(lastTranslated, t);
        return t;
    }

    // ---- outputs -------------------------------------------------------------------------------------------------------

    private void pullVideo() {
        while (!closed) {
            AppSink sink = vsink;
            Sample sample = sink == null ? null : sink.pullSample();
            if (sample == null) {
                if (closed) {
                    return;
                }
                if (sink != null && pipelineEos && sink.isEOS()) {
                    videoDrained = true;
                    maybeFinish();
                }
                sleep(15);
                continue;
            }
            try {
                if (videoFlushPending.get()) {
                    continue; // sample from before a seek's flush
                }
                int gen = generation;
                Buffer buffer = sample.getBuffer();
                long pts = translate(buffer.getPresentationTimestamp());
                if (pts < minPtsNanos) {
                    continue;
                }
                Structure s = sample.getCaps().getStructure(0);
                int w = s.getInteger("width");
                int h = s.getInteger("height");
                int length = w * h * 4;
                ByteBuffer bb = buffer.map(false);
                try {
                    if (bb == null || bb.remaining() < length) {
                        continue;
                    }
                    FrameQueue.Frame f = new FrameQueue.Frame();
                    f.pixels = frames.borrow(length);
                    bb.get(f.pixels, 0, length);
                    f.generation = gen;
                    f.ptsNanos = pts;
                    f.width = w;
                    f.height = h;
                    f.length = length;
                    frames.push(f, 500);
                } finally {
                    buffer.unmap();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Log.warn("video sample failed", e);
            } finally {
                sample.dispose();
            }
        }
    }

    /** Releases frames when the client's audible timestamp reaches them (or immediately for silent media). */
    private static final boolean DEBUG = System.getenv("OTETV_WORKER_DEBUG") != null;

    private void schedule() {
        long lastDebug = 0;
        while (!closed) {
            try {
                if (DEBUG && System.nanoTime() - lastDebug > 1_000_000_000L) {
                    lastDebug = System.nanoTime();
                    FrameQueue.Frame head = frames.peek();
                    Log.info("sched gen=" + generation + " queue=" + frames.size() + " head=" + (head == null ? -1 : head.ptsNanos / 1_000_000)
                            + " audible=" + clock.audioPtsNow(generation) / 1_000_000 + " playing=" + playing + " poster=" + posterPending);
                }
                FrameQueue.Frame f = frames.peek();
                if (f == null) {
                    frames.await(10);
                    continue;
                }
                int gen = generation;
                if (f.generation != gen) {
                    frames.recycle(frames.poll());
                    continue;
                }
                if (posterPending) {
                    posterPending = false;
                    send(frames.poll());
                    continue;
                }
                if (!videoEnabled) {
                    frames.recycle(frames.poll());
                    continue;
                }
                if (!playing) {
                    frames.await(10);
                    continue;
                }
                if (!hasAudio) {
                    send(frames.poll());
                    continue;
                }
                long audioNow = clock.audioPtsNow(gen);
                if (audioNow < 0) {
                    frames.await(5);
                    continue;
                }
                if (f.ptsNanos <= audioNow + FRAME_LEAD_NANOS) {
                    FrameQueue.Frame next = frames.peekSecond();
                    while (next != null && next.generation == gen && next.ptsNanos <= audioNow + FRAME_LEAD_NANOS) {
                        frames.recycle(frames.poll());
                        f = next;
                        next = frames.peekSecond();
                    }
                    send(frames.poll());
                } else {
                    long waitMs = (f.ptsNanos - audioNow - FRAME_LEAD_NANOS) / 1_000_000L;
                    frames.await(Math.max(1, Math.min(10, waitMs)));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void send(FrameQueue.Frame f) {
        if (f == null) {
            return;
        }
        out.frame(f.generation, sequence.incrementAndGet(), f.ptsNanos, f.width, f.height, f.pixels, f.length);
        lastFramePts = f.ptsNanos;
        lastOutputNanos = System.nanoTime();
        frames.recycle(f);
    }

    private void pullAudio() {
        while (!closed) {
            AppSink sink = asink;
            Sample sample = sink == null ? null : sink.pullSample();
            if (sample == null) {
                if (closed) {
                    return;
                }
                if (sink != null && pipelineEos && sink.isEOS()) {
                    audioDrained = true;
                    maybeFinish();
                }
                sleep(15);
                continue;
            }
            if (audioFlushPending.get()) {
                sample.dispose();
                continue;
            }
            int gen = generation;
            long pts;
            byte[] pcm;
            try {
                Buffer buffer = sample.getBuffer();
                pts = translate(buffer.getPresentationTimestamp());
                ByteBuffer bb = buffer.map(false);
                try {
                    pcm = new byte[bb == null ? 0 : bb.remaining()];
                    if (bb != null) {
                        bb.get(pcm);
                    }
                } finally {
                    buffer.unmap();
                }
            } catch (RuntimeException e) {
                Log.warn("audio sample failed", e);
                continue;
            } finally {
                sample.dispose();
            }
            if (pts < minPtsNanos || pcm.length == 0) {
                continue;
            }
            synchronized (creditLock) {
                try {
                    while (!closed && gen == generation && sentAudioBytes + pcm.length > consumedAudioBytes + AUDIO_WINDOW_BYTES) {
                        creditLock.wait(100);
                    }
                } catch (InterruptedException e) {
                    return;
                }
                if (closed || gen != generation) {
                    continue;
                }
                sentAudioBytes += pcm.length;
            }
            out.audio(gen, pts, pcm, pcm.length);
            lastAudioPts = pts;
            lastOutputNanos = System.nanoTime();
        }
    }

    private void pullText() {
        while (!closed) {
            AppSink sink = tsink;
            Sample sample = sink == null ? null : sink.pullSample();
            if (sample == null) {
                if (closed) {
                    return;
                }
                sleep(50);
                continue;
            }
            try {
                if (textFlushPending.get()) {
                    continue;
                }
                Buffer buffer = sample.getBuffer();
                long pts = translate(buffer.getPresentationTimestamp());
                long duration = Math.max(500_000_000L, buffer.getDuration());
                ByteBuffer bb = buffer.map(false);
                String text;
                try {
                    byte[] raw = new byte[Math.min(bb.remaining(), Msg.MAX_TEXT)];
                    bb.get(raw);
                    text = new String(raw, StandardCharsets.UTF_8).replaceAll("<[^>]{0,64}>", "");
                } finally {
                    buffer.unmap();
                }
                long start = Math.max(0, pts / 1_000_000L);
                if (!text.isBlank()) {
                    out.send(IpcType.CUE, new Msg.Cue(generation, start, start + duration / 1_000_000L, text).write());
                }
            } catch (RuntimeException e) {
                Log.warn("text sample failed", e);
            } finally {
                sample.dispose();
            }
        }
    }

    private void loadSidecar() {
        try {
            SafeUrl url = SafeUrl.parse(grant.captionsLocation());
            try (HttpResponse r = httpClient().execute(new SafeHttpClient.Request(url).maxBodyBytes(CaptionParser.MAX_BYTES).timeouts(10_000, 15_000), token)) {
                if (r.status() != 200) {
                    Log.info("captions HTTP " + r.status());
                    return;
                }
                List<Cue> cues = CaptionParser.parse(r.readAll());
                int gen = generation;
                for (Cue cue : cues) {
                    out.send(IpcType.CUE, new Msg.Cue(gen, cue.startMs(), cue.endMs(), cue.text()).write());
                }
                hasSidecar = true;
                sendCaps();
            }
        } catch (Exception e) {
            Log.info("captions unavailable: " + e.getMessage());
        }
    }

    // ---- control -----------------------------------------------------------------------------------------------------

    void play() {
        playing = true; // after EOS this changes nothing until a seek: the pipeline stays at its end
        if (prerolled) {
            applyPlayState();
        }
    }

    void pause() {
        playing = false;
        clock.freeze(generation);
        if (prerolled) {
            applyPlayState();
        }
    }

    void seek(int newGeneration, long positionMs) {
        placedAtMs = positionMs;
        boolean flushNow = seekable && prerolled;
        if (flushNow) {
            raiseFlushBarriers();
        }
        resetGeneration(newGeneration);
        minPtsNanos = positionMs * 1_000_000L - STALE_NANOS;
        if (!seekable) {
            return; // the client never seeks a non-seekable source; HLS is re-opened by the caller
        }
        if (!prerolled) {
            pendingSeekMs = positionMs;
            return;
        }
        doSeek(positionMs);
    }

    private void raiseFlushBarriers() {
        if (vsink != null) {
            videoFlushPending.set(true);
        }
        if (asink != null) {
            audioFlushPending.set(true);
        }
        if (tsink != null) {
            textFlushPending.set(true);
        }
    }

    private void doSeek(long positionMs) {
        synchronized (finishLock) {
            ended = false;
            pipelineEos = false;
            audioDrained = false;
            videoDrained = false;
        }
        raiseFlushBarriers();
        boolean ok = pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE), SeekType.SET,
                positionMs * 1_000_000L, SeekType.NONE, -1);
        if (!ok) {
            Log.info("seek to " + positionMs + " ms was refused");
            videoFlushPending.set(false);
            audioFlushPending.set(false);
            textFlushPending.set(false);
        }
        applyPlayState();
    }

    private void resetGeneration(int newGeneration) {
        synchronized (creditLock) {
            generation = newGeneration;
            sentAudioBytes = 0;
            consumedAudioBytes = 0;
            creditLock.notifyAll();
        }
        clock.reset(newGeneration);
        frames.clear();
        posterPending = true;
        lastFramePts = -1;
        lastAudioPts = -1;
    }

    void credit(Msg.AudioCredit credit) {
        synchronized (creditLock) {
            if (credit.generation() == generation) {
                consumedAudioBytes = credit.consumedBytes();
                creditLock.notifyAll();
            }
        }
    }

    void setVideoEnabled(boolean enabled) {
        videoEnabled = enabled;
    }

    int generation() {
        return generation;
    }

    // ---- reporting -----------------------------------------------------------------------------------------------------

    private void startReporter() {
        reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "otetv reporter");
            t.setDaemon(true);
            return t;
        });
        reporter.scheduleAtFixedRate(() -> {
            maybeFinish(); // the last queued frame may have been released since the sinks drained
            sendState();
        }, 100, 250, TimeUnit.MILLISECONDS);
    }

    private void sendCaps() {
        java.util.EnumSet<PlaybackCapabilities.Flag> f = java.util.EnumSet.of(PlaybackCapabilities.Flag.PLAY, PlaybackCapabilities.Flag.TEXTURE_OUTPUT,
                PlaybackCapabilities.Flag.EFFECTS_ELIGIBLE, PlaybackCapabilities.Flag.FRAME_SYNC, PlaybackCapabilities.Flag.LOCAL_VOLUME);
        if (!live) {
            f.add(PlaybackCapabilities.Flag.PAUSE);
        }
        if (seekable && !live) {
            f.add(PlaybackCapabilities.Flag.SEEK);
        }
        if (live) {
            f.add(PlaybackCapabilities.Flag.LIVE);
        }
        if (hasAudio) {
            f.add(PlaybackCapabilities.Flag.HAS_AUDIO);
        }
        if (hasVideo) {
            f.add(PlaybackCapabilities.Flag.HAS_VIDEO);
        }
        if (hasText) {
            f.add(PlaybackCapabilities.Flag.TEXT_TRACKS);
        }
        if (hasText || hasSidecar) {
            f.add(PlaybackCapabilities.Flag.CAPTIONS);
        }
        if (audioPadsSeen > 1) {
            f.add(PlaybackCapabilities.Flag.AUDIO_TRACKS);
        }
        out.send(IpcType.CAPS, new Msg.Caps(generation, PlaybackCapabilities.of(f, durationMs).toBits(), durationMs).write());
    }

    private void sendState() {
        if (closed) {
            return;
        }
        int gen = generation;
        LocalPlaybackState state;
        if (failed) {
            state = LocalPlaybackState.ERROR;
        } else if (ended) {
            state = LocalPlaybackState.ENDED;
        } else if (!prerolled) {
            state = LocalPlaybackState.LOADING;
        } else if (playing) {
            boolean starving = System.nanoTime() - lastOutputNanos > 1_500_000_000L;
            state = starving ? LocalPlaybackState.BUFFERING : LocalPlaybackState.PLAYING;
        } else {
            state = LocalPlaybackState.PAUSED;
        }
        long pos;
        long audible = clock.audioPtsNow(gen);
        if (hasAudio && audible >= 0) {
            pos = audible / 1_000_000L;
        } else if (lastFramePts >= 0) {
            pos = lastFramePts / 1_000_000L;
        } else {
            pos = Math.max(0, placedAtMs);
        }
        int flags = (live ? Msg.STATE_LIVE : 0) | (seekable ? Msg.STATE_SEEKABLE : 0) | (hasAudio ? Msg.STATE_HAS_AUDIO : 0)
                | (hasVideo ? Msg.STATE_HAS_VIDEO : 0) | (state == LocalPlaybackState.BUFFERING ? Msg.STATE_BUFFERING : 0);
        long duration = durationMs > 0 ? durationMs : -1;
        out.send(IpcType.STATE, new Msg.State(gen, state.ordinal(), Math.max(-1, Math.min(pos, com.otectus.otetv.core.Limits.MAX_POSITION_MS)),
                duration, flags, 100, outWidth, outHeight).write());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        token.cancel();
        synchronized (creditLock) {
            creditLock.notifyAll();
        }
        if (reporter != null) {
            reporter.shutdownNow();
        }
        if (pipeline != null) {
            try {
                pipeline.stop();
                pipeline.dispose();
            } catch (RuntimeException e) {
                Log.warn("pipeline teardown", e);
            }
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        if (source != null) {
            try {
                source.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        frames.clear();
    }
}
