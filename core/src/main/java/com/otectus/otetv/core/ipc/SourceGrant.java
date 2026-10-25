package com.otectus.otetv.core.ipc;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.net.LanGrant;

import java.util.ArrayList;
import java.util.List;

/**
 * What the client allows a media worker to open (section 11.1): a validated https URL or an explicitly granted local
 * file path, plus the local LAN grants that apply. A worker never receives an executable, a pipeline string or a
 * {@code file://}, SMB, FTP, RTSP or torrent URI. The worker re-validates every field it receives.
 */
public record SourceGrant(int generation, Kind kind, String location, String captionsLocation, List<LanGrant> lanGrants,
                          int maxWidth, int maxHeight, int maxFps, long startPositionMs, int audioTrack, boolean startPaused,
                          boolean videoEnabled, boolean live) {
    public enum Kind { FILE, HTTPS, HLS }

    public static final int MAX_LOCATION = 4096;
    public static final int MAX_LAN_GRANTS = 8;

    public SourceGrant {
        lanGrants = List.copyOf(lanGrants);
        if (lanGrants.size() > MAX_LAN_GRANTS) {
            throw new IllegalArgumentException("too many LAN grants");
        }
        if (location == null || location.isEmpty() || location.length() > MAX_LOCATION) {
            throw new IllegalArgumentException("bad location");
        }
        if (captionsLocation != null && captionsLocation.length() > MAX_LOCATION) {
            throw new IllegalArgumentException("bad captions location");
        }
    }

    public void write(PayloadWriter w) {
        w.i32(generation).u8(kind.ordinal()).str(location).str(captionsLocation == null ? "" : captionsLocation);
        w.u8(lanGrants.size());
        for (LanGrant g : lanGrants) {
            w.str(g.host()).i32(g.port());
        }
        w.i32(maxWidth).i32(maxHeight).i32(maxFps).i64(startPositionMs).i32(audioTrack);
        w.u8((startPaused ? 1 : 0) | (videoEnabled ? 2 : 0) | (live ? 4 : 0));
    }

    public static SourceGrant read(PayloadReader r) throws IpcProtocolException {
        int generation = r.i32();
        Kind kind = r.enumValue(Kind.values());
        String location = r.str(MAX_LOCATION);
        String captions = r.str(MAX_LOCATION);
        int grants = r.u8();
        if (grants > MAX_LAN_GRANTS) {
            throw new IpcProtocolException("too many LAN grants");
        }
        List<LanGrant> list = new ArrayList<>(grants);
        for (int i = 0; i < grants; i++) {
            String host = r.str(253);
            int port = r.i32(1, 65535);
            try {
                list.add(new LanGrant(host, port));
            } catch (IllegalArgumentException e) {
                throw new IpcProtocolException("bad LAN grant");
            }
        }
        int maxWidth = r.i32(64, Limits.MAX_FRAME_WIDTH);
        int maxHeight = r.i32(36, Limits.MAX_FRAME_HEIGHT);
        int maxFps = r.i32(1, 60);
        long start = r.i64(0, Limits.MAX_POSITION_MS);
        int audioTrack = r.i32(0, 63);
        int flags = r.u8();
        r.expectEnd();
        try {
            return new SourceGrant(generation, kind, location, captions.isEmpty() ? null : captions, list, maxWidth, maxHeight,
                    maxFps, start, audioTrack, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
        } catch (IllegalArgumentException e) {
            throw new IpcProtocolException(e.getMessage());
        }
    }
}
