package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.hls.HlsException;
import com.otectus.otetv.core.hls.HlsParser;
import com.otectus.otetv.core.hls.HlsPlaylist;
import com.otectus.otetv.core.net.CancellationToken;
import com.otectus.otetv.core.net.DestinationBlockedException;
import com.otectus.otetv.core.net.HttpResponse;
import com.otectus.otetv.core.net.SafeHttpClient;
import com.otectus.otetv.core.net.SafeUrl;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Clear HLS through the guarded fetcher (section 11.3): every playlist, initialization map and segment is fetched by
 * this class with destination checks; GStreamer only sees one continuous byte stream through appsrc, and its own
 * adaptive demuxers are disabled. Encrypted playlists, nested references and separate audio renditions are refused
 * with precise reasons. VOD starts at the segment containing the requested position; live starts three target
 * durations from the edge (RFC 8216).
 */
final class HlsByteSource implements ByteSource {
    static final class HlsRejected extends IOException {
        final HlsException.Reason reason;

        HlsRejected(HlsException.Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private static final long MAX_PLAYLIST = HlsParser.MAX_BYTES;
    private static final long MAX_SEGMENT = 64L * 1024 * 1024;

    private final SafeHttpClient client;
    private final CancellationToken token;
    private final SafeUrl mediaUrl;
    private HlsPlaylist.Media playlist;
    private final List<HlsPlaylist.Segment> pending = new ArrayList<>();
    private long nextSequence;
    private long timelineOffsetNanos;
    private SafeUrl currentMap;
    private InputStream current;
    private HttpResponse currentResponse;
    private long position;
    private long lastReloadNanos;
    private long lastGrowthNanos;
    private boolean ended;

    HlsByteSource(SafeHttpClient client, SafeUrl url, CancellationToken token, int maxHeight, long startMs) throws IOException {
        this.client = client;
        this.token = token;
        HlsPlaylist first = fetchPlaylist(url);
        SafeUrl media = url;
        if (first instanceof HlsPlaylist.Master master) {
            HlsPlaylist.Variant chosen = choose(master, maxHeight);
            media = chosen.uri();
            first = fetchPlaylist(media);
            if (!(first instanceof HlsPlaylist.Media)) {
                throw new HlsRejected(HlsException.Reason.UNSUPPORTED, "variant is not a media playlist");
            }
        }
        this.mediaUrl = media;
        this.playlist = (HlsPlaylist.Media) first;
        List<HlsPlaylist.Segment> segments = playlist.segments();
        if (segments.isEmpty()) {
            throw new HlsRejected(HlsException.Reason.MALFORMED, "playlist has no segments yet");
        }
        int start;
        if (playlist.isLive()) {
            start = playlist.liveStartIndex();
            timelineOffsetNanos = 0;
        } else {
            start = playlist.segmentIndexAt(startMs / 1000.0);
            timelineOffsetNanos = (long) (playlist.segmentStartSeconds(start) * 1_000_000_000L);
        }
        for (int i = start; i < segments.size(); i++) {
            pending.add(segments.get(i));
        }
        nextSequence = segments.get(segments.size() - 1).sequence() + 1;
        lastReloadNanos = System.nanoTime();
        lastGrowthNanos = lastReloadNanos;
        Log.info("HLS " + (playlist.isLive() ? "live" : "VOD") + " playlist with " + segments.size() + " segments, starting at " + start);
    }

    private static HlsPlaylist.Variant choose(HlsPlaylist.Master master, int maxHeight) throws HlsRejected {
        HlsPlaylist.Variant best = null;
        HlsPlaylist.Variant smallest = null;
        for (HlsPlaylist.Variant v : master.variants()) {
            boolean separateAudio = v.audioGroup() != null && master.renditions().stream()
                    .anyMatch(r -> "AUDIO".equals(r.type()) && v.audioGroup().equals(r.groupId()) && r.uri() != null);
            if (separateAudio) {
                continue; // muxed variants only in this release
            }
            if (smallest == null || v.bandwidth() < smallest.bandwidth()) {
                smallest = v;
            }
            boolean fits = !v.hasResolution() || v.height() <= maxHeight;
            if (fits && (best == null || v.bandwidth() > best.bandwidth())) {
                best = v;
            }
        }
        if (best == null) {
            best = smallest;
        }
        if (best == null) {
            throw new HlsRejected(HlsException.Reason.UNSUPPORTED, "only variants with separate audio renditions are offered");
        }
        return best;
    }

    private HlsPlaylist fetchPlaylist(SafeUrl url) throws IOException {
        try (HttpResponse r = client.execute(new SafeHttpClient.Request(url).maxBodyBytes(MAX_PLAYLIST).timeouts(10_000, 15_000)
                .accept("application/vnd.apple.mpegurl, application/x-mpegurl, */*;q=0.5"), token)) {
            if (r.status() != 200) {
                throw new IOException("playlist HTTP " + r.status());
            }
            return HlsParser.parse(r.readAll(), r.url());
        } catch (DestinationBlockedException e) {
            throw new IOException("playlist destination blocked: " + e.category());
        } catch (HlsException e) {
            throw new HlsRejected(e.reason(), e.getMessage());
        }
    }

    private boolean openNext() throws IOException {
        closeCurrent();
        while (pending.isEmpty()) {
            if (!playlist.isLive()) {
                ended = true;
                return false;
            }
            reloadLive();
            if (pending.isEmpty()) {
                if (System.nanoTime() - lastGrowthNanos > (long) (playlist.targetDuration() * 3_000_000_000L)) {
                    throw new IOException("live playlist stopped updating");
                }
                try {
                    Thread.sleep((long) Math.max(500, playlist.targetDuration() * 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }
        HlsPlaylist.Segment seg = pending.remove(0);
        // Initialization maps (fMP4) are sent once whenever they change.
        if (seg.initMap() != null && !seg.initMap().equals(currentMap)) {
            currentMap = seg.initMap();
            current = open(seg.initMap(), seg.initMapRange());
            pending.add(0, new HlsPlaylist.Segment(seg.uri(), seg.durationSeconds(), seg.sequence(), seg.discontinuity(), seg.byteRange(),
                    seg.initMap(), seg.initMapRange(), seg.programDateTime()));
            return true;
        }
        current = open(seg.uri(), seg.byteRange());
        return true;
    }

    private InputStream open(SafeUrl uri, HlsPlaylist.ByteRange range) throws IOException {
        SafeHttpClient.Request req = new SafeHttpClient.Request(uri).maxBodyBytes(MAX_SEGMENT).timeouts(10_000, 20_000);
        if (range != null) {
            req.range(range.offset(), range.offset() + range.length() - 1);
        }
        try {
            currentResponse = client.execute(req, token);
        } catch (DestinationBlockedException e) {
            throw new IOException("segment destination blocked: " + e.category());
        }
        int status = currentResponse.status();
        if (status != 200 && status != 206) {
            throw new IOException("segment HTTP " + status);
        }
        if (currentResponse.contentType().startsWith("text/html")) {
            throw new IOException("segment is a web page");
        }
        return currentResponse.body();
    }

    private void reloadLive() throws IOException {
        long now = System.nanoTime();
        long wait = (long) (playlist.targetDuration() * 500_000_000L);
        if (now - lastReloadNanos < wait) {
            return;
        }
        lastReloadNanos = now;
        HlsPlaylist p = fetchPlaylist(mediaUrl);
        if (!(p instanceof HlsPlaylist.Media media)) {
            throw new HlsRejected(HlsException.Reason.UNSUPPORTED, "playlist type changed");
        }
        playlist = media;
        for (HlsPlaylist.Segment s : media.segments()) {
            if (s.sequence() >= nextSequence) {
                pending.add(s);
                nextSequence = s.sequence() + 1;
                lastGrowthNanos = now;
            }
        }
    }

    @Override
    public long size() {
        return -1;
    }

    @Override
    public boolean seekable() {
        return false;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        while (true) {
            token.throwIfCancelled();
            if (current == null && !openNext()) {
                return -1;
            }
            int n = current.read(buf, off, len);
            if (n > 0) {
                position += n;
                return n;
            }
            closeCurrent();
            if (ended) {
                return -1;
            }
        }
    }

    @Override
    public void seek(long target) throws IOException {
        throw new IOException("HLS is re-opened at a segment instead of byte-seeked");
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public long timelineOffsetNanos() {
        return timelineOffsetNanos;
    }

    @Override
    public boolean isLive() {
        return playlist.isLive();
    }

    @Override
    public long knownDurationMs() {
        return playlist.isLive() ? -1 : (long) (playlist.totalDurationSeconds() * 1000);
    }

    private void closeCurrent() {
        if (currentResponse != null) {
            currentResponse.close();
            currentResponse = null;
        }
        current = null;
    }

    @Override
    public void close() {
        closeCurrent();
    }
}
