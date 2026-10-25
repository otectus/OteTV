package com.otectus.otetv.core.ipc;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed payload schemas for every worker IPC message, shared by the client and both workers so they cannot drift.
 * Each record has {@code write} (payload) and a static {@code read} that validates bounds and rejects trailing bytes.
 */
public final class Msg {
    public static final int ROLE_MEDIA = 0;
    public static final int ROLE_BROWSER = 1;

    public static final int STATE_LIVE = 1;
    public static final int STATE_SEEKABLE = 2;
    public static final int STATE_HAS_AUDIO = 4;
    public static final int STATE_HAS_VIDEO = 8;
    public static final int STATE_BUFFERING = 16;

    public static final int CLOCK_AUDIO_ACTIVE = 1;
    public static final int CLOCK_PLAYING = 2;

    public static final int MAX_ELEMENTS = 512;
    public static final int MAX_TEXT = 1024;

    private Msg() {
    }

    // ---- client -> worker -------------------------------------------------------------------------------------------

    public record Hello(int protocol, String clientVersion, int role, boolean safeMode) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(protocol).str(clientVersion).u8(role).bool(safeMode);
        }

        public static Hello read(PayloadReader r) throws IpcProtocolException {
            Hello h = new Hello(r.i32(), r.str(64), r.u8(), r.bool());
            r.expectEnd();
            return h;
        }
    }

    public record Gen(int generation) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation);
        }

        public static Gen read(PayloadReader r) throws IpcProtocolException {
            Gen g = new Gen(r.i32());
            r.expectEnd();
            return g;
        }
    }

    public record Seek(int generation, long positionMs, boolean accurate) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).i64(positionMs).bool(accurate);
        }

        public static Seek read(PayloadReader r) throws IpcProtocolException {
            Seek s = new Seek(r.i32(), r.i64(0, com.otectus.otetv.core.Limits.MAX_POSITION_MS), r.bool());
            r.expectEnd();
            return s;
        }
    }

    public record SetOutput(int width, int height, int fps) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(width).i32(height).i32(fps);
        }

        public static SetOutput read(PayloadReader r) throws IpcProtocolException {
            SetOutput s = new SetOutput(r.i32(64, FrameHeader.MAX_WIDTH), r.i32(36, FrameHeader.MAX_HEIGHT), r.i32(1, 60));
            r.expectEnd();
            return s;
        }
    }

    public record Flag(boolean value) {
        public PayloadWriter write() {
            return new PayloadWriter().bool(value);
        }

        public static Flag read(PayloadReader r) throws IpcProtocolException {
            Flag f = new Flag(r.bool());
            r.expectEnd();
            return f;
        }
    }

    /** The client's audio clock: which media timestamp is audible at a given client monotonic time. */
    public record ClockReport(int generation, long clientNanos, long audioPtsNanos, int flags) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).i64(clientNanos).i64(audioPtsNanos).u8(flags);
        }

        public static ClockReport read(PayloadReader r) throws IpcProtocolException {
            ClockReport c = new ClockReport(r.i32(), r.i64(), r.i64(), r.u8());
            r.expectEnd();
            return c;
        }
    }

    /** Cumulative bytes of PCM the client has consumed in this generation: the worker's send window. */
    public record AudioCredit(int generation, long consumedBytes) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).i64(consumedBytes);
        }

        public static AudioCredit read(PayloadReader r) throws IpcProtocolException {
            AudioCredit c = new AudioCredit(r.i32(), r.i64(0, Long.MAX_VALUE / 2));
            r.expectEnd();
            return c;
        }
    }

    public record SetRate(int generation, double rate) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).f64(rate);
        }

        public static SetRate read(PayloadReader r) throws IpcProtocolException {
            int g = r.i32();
            double rate = r.f64();
            if (rate < 0.9 || rate > 1.1) {
                throw new IpcProtocolException("rate out of range");
            }
            r.expectEnd();
            return new SetRate(g, rate);
        }
    }

    public record SelectTrack(int generation, int kind, int index) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(kind).i32(index);
        }

        public static SelectTrack read(PayloadReader r) throws IpcProtocolException {
            SelectTrack s = new SelectTrack(r.i32(), r.u8(), r.i32(-1, 63));
            r.expectEnd();
            return s;
        }
    }

    public record Ping(long nonce, long clientNanos) {
        public PayloadWriter write() {
            return new PayloadWriter().i64(nonce).i64(clientNanos);
        }

        public static Ping read(PayloadReader r) throws IpcProtocolException {
            Ping p = new Ping(r.i64(), r.i64());
            r.expectEnd();
            return p;
        }
    }

    /** Browser worker: load the official player for one validated video id. No URL or HTML crosses this boundary. */
    public record LoadVideo(int generation, String videoId, int startSeconds, boolean autoplay, boolean captions) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).str(videoId).i32(startSeconds).bool(autoplay).bool(captions);
        }

        public static LoadVideo read(PayloadReader r) throws IpcProtocolException {
            int g = r.i32();
            String id = r.str(11);
            if (!id.matches("[A-Za-z0-9_-]{11}")) {
                throw new IpcProtocolException("bad video id");
            }
            LoadVideo l = new LoadVideo(g, id, r.i32(0, com.otectus.otetv.core.Limits.MAX_START_SECONDS), r.bool(), r.bool());
            r.expectEnd();
            return l;
        }
    }

    public record Mouse(int generation, int kind, int x, int y, int button, int modifiers, int wheel) {
        public static final int MOVE = 0;
        public static final int DOWN = 1;
        public static final int UP = 2;
        public static final int WHEEL = 3;
        public static final int LEAVE = 4;

        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(kind).i32(x).i32(y).u8(button).i32(modifiers).i32(wheel);
        }

        public static Mouse read(PayloadReader r) throws IpcProtocolException {
            Mouse m = new Mouse(r.i32(), r.u8(), r.i32(-1, FrameHeader.MAX_WIDTH), r.i32(-1, FrameHeader.MAX_HEIGHT), r.u8(), r.i32(), r.i32(-10_000, 10_000));
            if (m.kind > LEAVE || m.button > 2) {
                throw new IpcProtocolException("bad mouse event");
            }
            r.expectEnd();
            return m;
        }
    }

    public record Key(int generation, int kind, int keyCode, int scanCode, int modifiers, int codePoint) {
        public static final int DOWN = 0;
        public static final int UP = 1;
        public static final int CHAR = 2;

        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(kind).i32(keyCode).i32(scanCode).i32(modifiers).i32(codePoint);
        }

        public static Key read(PayloadReader r) throws IpcProtocolException {
            Key k = new Key(r.i32(), r.u8(), r.i32(-1, 1024), r.i32(), r.i32(), r.i32(0, 0x10FFFF));
            if (k.kind > CHAR) {
                throw new IpcProtocolException("bad key event");
            }
            r.expectEnd();
            return k;
        }
    }

    public record SetVolume(int generation, int percent, boolean muted) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(percent).bool(muted);
        }

        public static SetVolume read(PayloadReader r) throws IpcProtocolException {
            SetVolume v = new SetVolume(r.i32(), r.u8(), r.bool());
            if (v.percent > 100) {
                throw new IpcProtocolException("volume out of range");
            }
            r.expectEnd();
            return v;
        }
    }

    public record Resize(int width, int height) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(width).i32(height);
        }

        public static Resize read(PayloadReader r) throws IpcProtocolException {
            Resize s = new Resize(r.i32(200, FrameHeader.MAX_WIDTH), r.i32(200, FrameHeader.MAX_HEIGHT));
            r.expectEnd();
            return s;
        }
    }

    // ---- worker -> client -------------------------------------------------------------------------------------------

    public record HelloAck(int protocol, String workerVersion, String runtimeVersion, int flags, List<String> elements) {
        public static final int FLAG_RATE_CONTROL = 1;
        public static final int FLAG_SANDBOXED = 2;

        public PayloadWriter write() {
            PayloadWriter w = new PayloadWriter(1024).i32(protocol).str(workerVersion).str(runtimeVersion).i32(flags);
            w.u16(Math.min(elements.size(), MAX_ELEMENTS));
            for (int i = 0; i < Math.min(elements.size(), MAX_ELEMENTS); i++) {
                w.str(elements.get(i));
            }
            return w;
        }

        public static HelloAck read(PayloadReader r) throws IpcProtocolException {
            int protocol = r.i32();
            String wv = r.str(64);
            String rv = r.str(128);
            int flags = r.i32();
            int n = r.u16();
            if (n > MAX_ELEMENTS) {
                throw new IpcProtocolException("too many elements");
            }
            List<String> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(r.str(64));
            }
            r.expectEnd();
            return new HelloAck(protocol, wv, rv, flags, list);
        }
    }

    public record State(int generation, int state, long positionMs, long durationMs, int flags, int bufferingPercent, int videoWidth, int videoHeight) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(state).i64(positionMs).i64(durationMs).u8(flags).u8(bufferingPercent).i32(videoWidth).i32(videoHeight);
        }

        public static State read(PayloadReader r) throws IpcProtocolException {
            State s = new State(r.i32(), r.u8(), r.i64(-1, com.otectus.otetv.core.Limits.MAX_POSITION_MS),
                    r.i64(-1, com.otectus.otetv.core.Limits.MAX_POSITION_MS), r.u8(), r.u8(), r.i32(0, 16384), r.i32(0, 16384));
            r.expectEnd();
            return s;
        }
    }

    public record Caps(int generation, long bits, long durationMs) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).i64(bits).i64(durationMs);
        }

        public static Caps read(PayloadReader r) throws IpcProtocolException {
            Caps c = new Caps(r.i32(), r.i64(), r.i64(-1, com.otectus.otetv.core.Limits.MAX_POSITION_MS));
            r.expectEnd();
            return c;
        }
    }

    /** Header for AUDIO_CHUNK; the PCM bytes follow in the same payload. */
    public record AudioHeader(int generation, long ptsNanos, int sampleRate, int channels, int byteLength) {
        public static final int BYTES = 4 + 8 + 4 + 1 + 4;

        public PayloadWriter write() {
            return new PayloadWriter(BYTES).i32(generation).i64(ptsNanos).i32(sampleRate).u8(channels).i32(byteLength);
        }

        public static AudioHeader read(PayloadReader r) throws IpcProtocolException {
            AudioHeader h = new AudioHeader(r.i32(), r.i64(), r.i32(8000, 192_000), r.u8(), r.i32(0, 1024 * 1024));
            if (h.channels < 1 || h.channels > 2 || h.byteLength % (2 * h.channels) != 0) {
                throw new IpcProtocolException("bad audio layout");
            }
            return h;
        }
    }

    public record Cue(int generation, long startMs, long endMs, String text) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).i64(startMs).i64(endMs).str(text);
        }

        public static Cue read(PayloadReader r) throws IpcProtocolException {
            Cue c = new Cue(r.i32(), r.i64(0, com.otectus.otetv.core.Limits.MAX_POSITION_MS), r.i64(0, com.otectus.otetv.core.Limits.MAX_POSITION_MS), r.str(MAX_TEXT));
            if (c.endMs <= c.startMs) {
                throw new IpcProtocolException("bad cue timing");
            }
            r.expectEnd();
            return c;
        }
    }

    public record Track(int kind, int index, String label, String language) {
        public static final int AUDIO = 0;
        public static final int TEXT = 1;
    }

    public record Tracks(int generation, List<Track> tracks) {
        public PayloadWriter write() {
            PayloadWriter w = new PayloadWriter().i32(generation).u8(Math.min(tracks.size(), 64));
            for (int i = 0; i < Math.min(tracks.size(), 64); i++) {
                Track t = tracks.get(i);
                w.u8(t.kind()).i32(t.index()).str(t.label()).str(t.language());
            }
            return w;
        }

        public static Tracks read(PayloadReader r) throws IpcProtocolException {
            int g = r.i32();
            int n = r.u8();
            if (n > 64) {
                throw new IpcProtocolException("too many tracks");
            }
            List<Track> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Track(r.u8(), r.i32(0, 63), r.str(128), r.str(16)));
            }
            r.expectEnd();
            return new Tracks(g, list);
        }
    }

    public record Error(int generation, int code, String detail) {
        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u16(code).str(detail.length() > MAX_TEXT ? detail.substring(0, MAX_TEXT) : detail);
        }

        public static Error read(PayloadReader r) throws IpcProtocolException {
            Error e = new Error(r.i32(), r.u16(), r.str(MAX_TEXT * 4));
            r.expectEnd();
            return e;
        }
    }

    public record Pong(long nonce, long clientNanos, long workerNanos) {
        public PayloadWriter write() {
            return new PayloadWriter().i64(nonce).i64(clientNanos).i64(workerNanos);
        }

        public static Pong read(PayloadReader r) throws IpcProtocolException {
            Pong p = new Pong(r.i64(), r.i64(), r.i64());
            r.expectEnd();
            return p;
        }
    }

    /** Typed events from the official YouTube player wrapper (section 12.3); never arbitrary script results. */
    public record PlayerEvent(int generation, int event, long value, String detail) {
        public static final int READY = 0;
        public static final int STATE = 1;
        public static final int ERROR = 2;
        public static final int AUTOPLAY_BLOCKED = 3;
        public static final int TIME = 4;
        public static final int DURATION = 5;
        public static final int API_CHANGE = 6;
        public static final int CAPTIONS = 7;
        public static final int LOADED = 8;

        public PayloadWriter write() {
            return new PayloadWriter().i32(generation).u8(event).i64(value).str(detail.length() > 256 ? detail.substring(0, 256) : detail);
        }

        public static PlayerEvent read(PayloadReader r) throws IpcProtocolException {
            PlayerEvent e = new PlayerEvent(r.i32(), r.u8(), r.i64(), r.str(1024));
            if (e.event > LOADED) {
                throw new IpcProtocolException("unknown player event");
            }
            r.expectEnd();
            return e;
        }
    }
}
