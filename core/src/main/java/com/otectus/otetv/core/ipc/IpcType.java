package com.otectus.otetv.core.ipc;

/**
 * Worker IPC message types. Framing is {@code [int32 length][u8 type][payload]} on the worker's stdin/stdout; logs go
 * to stderr, never into this stream (section 16.3). Each type declares its direction and a maximum payload size that
 * is enforced before any allocation. No type carries JavaScript, HTML, a command line, a pipeline description or an
 * executable path.
 */
public enum IpcType {
    // client -> worker
    HELLO(0x01, Direction.TO_WORKER, 4096),
    OPEN(0x02, Direction.TO_WORKER, 16 * 1024),
    PLAY(0x03, Direction.TO_WORKER, 16),
    PAUSE(0x04, Direction.TO_WORKER, 16),
    SEEK(0x05, Direction.TO_WORKER, 32),
    STOP(0x06, Direction.TO_WORKER, 16),
    CLOSE(0x07, Direction.TO_WORKER, 16),
    SET_OUTPUT(0x08, Direction.TO_WORKER, 32),
    SET_VIDEO_ENABLED(0x09, Direction.TO_WORKER, 16),
    CLOCK_REPORT(0x0A, Direction.TO_WORKER, 64),
    AUDIO_CREDIT(0x0B, Direction.TO_WORKER, 32),
    SET_RATE(0x0C, Direction.TO_WORKER, 32),
    SELECT_TRACK(0x0D, Direction.TO_WORKER, 32),
    PING(0x0E, Direction.TO_WORKER, 32),
    SHUTDOWN(0x0F, Direction.TO_WORKER, 8),
    LOAD_VIDEO(0x10, Direction.TO_WORKER, 512),
    MOUSE(0x11, Direction.TO_WORKER, 64),
    KEY(0x12, Direction.TO_WORKER, 64),
    SET_VOLUME(0x13, Direction.TO_WORKER, 32),
    SET_VISIBLE(0x14, Direction.TO_WORKER, 16),
    RESIZE(0x15, Direction.TO_WORKER, 32),
    CAPTIONS_TOGGLE(0x16, Direction.TO_WORKER, 16),

    // worker -> client
    HELLO_ACK(0x40, Direction.TO_CLIENT, 64 * 1024),
    STATE(0x41, Direction.TO_CLIENT, 256),
    CAPS(0x42, Direction.TO_CLIENT, 64),
    VIDEO_FRAME(0x43, Direction.TO_CLIENT, FrameHeader.BYTES + 4096 * 2160 * 4),
    AUDIO_CHUNK(0x44, Direction.TO_CLIENT, 1024 * 1024),
    CUE(0x45, Direction.TO_CLIENT, 4096),
    CUES_CLEAR(0x46, Direction.TO_CLIENT, 16),
    TRACKS(0x47, Direction.TO_CLIENT, 16 * 1024),
    ERROR(0x48, Direction.TO_CLIENT, 2048),
    EOS(0x49, Direction.TO_CLIENT, 16),
    PONG(0x4A, Direction.TO_CLIENT, 64),
    PLAYER_EVENT(0x4B, Direction.TO_CLIENT, 2048),
    CURSOR(0x4C, Direction.TO_CLIENT, 16);

    public enum Direction { TO_WORKER, TO_CLIENT }

    private static final IpcType[] BY_CODE = new IpcType[256];

    static {
        for (IpcType t : values()) {
            BY_CODE[t.code] = t;
        }
    }

    private final int code;
    private final Direction direction;
    private final int maxPayload;

    IpcType(int code, Direction direction, int maxPayload) {
        this.code = code;
        this.direction = direction;
        this.maxPayload = maxPayload;
    }

    public int code() {
        return code;
    }

    public Direction direction() {
        return direction;
    }

    public int maxPayload() {
        return maxPayload;
    }

    public static IpcType byCode(int code) {
        return code >= 0 && code < 256 ? BY_CODE[code] : null;
    }
}
