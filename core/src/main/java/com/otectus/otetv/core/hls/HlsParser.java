package com.otectus.otetv.core.hls;

import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A bounded parser for the clear-HLS subset (RFC 8216). Encrypted playlists (any EXT-X-KEY or EXT-X-SESSION-KEY
 * method other than NONE) are rejected with a precise reason. Every URI is resolved against the playlist URL and
 * re-validated, so a playlist cannot point the fetcher at a non-http(s) scheme. Nesting is limited by the caller to
 * master -> media; a media playlist never references another playlist.
 */
public final class HlsParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_LINES = 40_000;
    public static final int MAX_SEGMENTS = 20_000;
    public static final int MAX_VARIANTS = 64;
    public static final int MAX_RENDITIONS = 64;

    private HlsParser() {
    }

    public static HlsPlaylist parse(byte[] data, SafeUrl base) throws HlsException {
        if (data.length > MAX_BYTES) {
            throw new HlsException(HlsException.Reason.TOO_LARGE, data.length + " bytes");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length > MAX_LINES) {
            throw new HlsException(HlsException.Reason.TOO_LARGE, lines.length + " lines");
        }
        if (lines.length == 0 || !lines[0].trim().equals("#EXTM3U")) {
            throw new HlsException(HlsException.Reason.MALFORMED, "missing #EXTM3U");
        }
        boolean master = false;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                master = true;
                break;
            }
        }
        return master ? parseMaster(lines, base) : parseMedia(lines, base);
    }

    private static HlsPlaylist.Master parseMaster(String[] lines, SafeUrl base) throws HlsException {
        List<HlsPlaylist.Variant> variants = new ArrayList<>();
        List<HlsPlaylist.Rendition> renditions = new ArrayList<>();
        Map<String, String> pendingStreamInf = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-SESSION-KEY")) {
                Map<String, String> attrs = attributes(after(line));
                if (!"NONE".equals(attrs.getOrDefault("METHOD", "NONE"))) {
                    throw new HlsException(HlsException.Reason.ENCRYPTED, "session key " + attrs.get("METHOD"));
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingStreamInf = attributes(after(line));
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attributes(after(line));
                String uri = a.get("URI");
                renditions.add(new HlsPlaylist.Rendition(a.getOrDefault("TYPE", ""), a.getOrDefault("GROUP-ID", ""),
                        a.getOrDefault("NAME", ""), a.getOrDefault("LANGUAGE", ""), uri == null ? null : resolve(base, uri),
                        "YES".equals(a.get("DEFAULT"))));
                if (renditions.size() > MAX_RENDITIONS) {
                    throw new HlsException(HlsException.Reason.TOO_LARGE, "renditions");
                }
            } else if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                continue;
            } else if (line.startsWith("#")) {
                continue;
            } else if (pendingStreamInf != null) {
                Map<String, String> a = pendingStreamInf;
                pendingStreamInf = null;
                long bandwidth = parseLong(a.get("BANDWIDTH"), -1);
                if (bandwidth <= 0) {
                    throw new HlsException(HlsException.Reason.MALFORMED, "variant without BANDWIDTH");
                }
                int w = 0;
                int h = 0;
                String res = a.get("RESOLUTION");
                if (res != null) {
                    String[] wh = res.toLowerCase(Locale.ROOT).split("x");
                    if (wh.length == 2) {
                        w = (int) Math.min(16384, parseLong(wh[0], 0));
                        h = (int) Math.min(16384, parseLong(wh[1], 0));
                    }
                }
                double fps = parseDouble(a.get("FRAME-RATE"), 0);
                variants.add(new HlsPlaylist.Variant(resolve(base, line), bandwidth, w, h, a.getOrDefault("CODECS", ""),
                        a.get("AUDIO"), fps));
                if (variants.size() > MAX_VARIANTS) {
                    throw new HlsException(HlsException.Reason.TOO_LARGE, "variants");
                }
            }
        }
        if (variants.isEmpty()) {
            throw new HlsException(HlsException.Reason.MALFORMED, "master playlist without variants");
        }
        return new HlsPlaylist.Master(List.copyOf(variants), List.copyOf(renditions));
    }

    private static HlsPlaylist.Media parseMedia(String[] lines, SafeUrl base) throws HlsException {
        double targetDuration = -1;
        long mediaSequence = 0;
        boolean endList = false;
        boolean independent = false;
        String playlistType = null;
        List<HlsPlaylist.Segment> segments = new ArrayList<>();
        double pendingDuration = -1;
        boolean pendingDiscontinuity = false;
        HlsPlaylist.ByteRange pendingRange = null;
        String pendingPdt = null;
        SafeUrl currentMap = null;
        HlsPlaylist.ByteRange currentMapRange = null;
        long nextRangeOffset = 0;
        SafeUrl lastRangeUri = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = parseDouble(after(line), -1);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLong(after(line), 0);
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                playlistType = after(line).trim().toUpperCase(Locale.ROOT);
            } else if (line.equals("#EXT-X-INDEPENDENT-SEGMENTS")) {
                independent = true;
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attributes(after(line));
                if (!"NONE".equals(a.getOrDefault("METHOD", ""))) {
                    throw new HlsException(HlsException.Reason.ENCRYPTED, a.getOrDefault("METHOD", "unknown"));
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attributes(after(line));
                String uri = a.get("URI");
                if (uri == null) {
                    throw new HlsException(HlsException.Reason.MALFORMED, "EXT-X-MAP without URI");
                }
                currentMap = resolve(base, uri);
                currentMapRange = a.containsKey("BYTERANGE") ? byteRange(a.get("BYTERANGE"), 0) : null;
            } else if (line.startsWith("#EXTINF:")) {
                String v = after(line);
                int comma = v.indexOf(',');
                pendingDuration = parseDouble(comma >= 0 ? v.substring(0, comma) : v, -1);
                if (pendingDuration < 0 || pendingDuration > 3600) {
                    throw new HlsException(HlsException.Reason.MALFORMED, "bad EXTINF");
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = byteRange(after(line), -1);
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = true;
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                pendingPdt = after(line).trim();
                if (pendingPdt.length() > 64) {
                    pendingPdt = null;
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-MEDIA:")) {
                throw new HlsException(HlsException.Reason.UNSUPPORTED, "nested playlist reference in media playlist");
            } else if (line.startsWith("#")) {
                continue;
            } else {
                if (pendingDuration < 0) {
                    throw new HlsException(HlsException.Reason.MALFORMED, "segment without EXTINF");
                }
                SafeUrl uri = resolve(base, line);
                String ext = uri.extension();
                if (ext.equals("m3u8") || ext.equals("m3u")) {
                    throw new HlsException(HlsException.Reason.UNSUPPORTED, "segment is a playlist");
                }
                HlsPlaylist.ByteRange range = pendingRange;
                if (range != null && range.offset() < 0) {
                    if (!uri.equals(lastRangeUri)) {
                        throw new HlsException(HlsException.Reason.MALFORMED, "byte range without offset");
                    }
                    range = new HlsPlaylist.ByteRange(range.length(), nextRangeOffset);
                }
                if (range != null) {
                    nextRangeOffset = range.offset() + range.length();
                    lastRangeUri = uri;
                }
                segments.add(new HlsPlaylist.Segment(uri, pendingDuration, mediaSequence + segments.size(), pendingDiscontinuity,
                        range, currentMap, currentMapRange, pendingPdt));
                if (segments.size() > MAX_SEGMENTS) {
                    throw new HlsException(HlsException.Reason.TOO_LARGE, "segments");
                }
                pendingDuration = -1;
                pendingDiscontinuity = false;
                pendingRange = null;
                pendingPdt = null;
            }
        }
        if (targetDuration <= 0) {
            throw new HlsException(HlsException.Reason.MALFORMED, "missing EXT-X-TARGETDURATION");
        }
        if (segments.isEmpty() && endList) {
            throw new HlsException(HlsException.Reason.MALFORMED, "empty playlist");
        }
        return new HlsPlaylist.Media(targetDuration, mediaSequence, endList, playlistType, List.copyOf(segments), independent);
    }

    private static HlsPlaylist.ByteRange byteRange(String spec, long defaultOffset) throws HlsException {
        String s = spec.trim();
        int at = s.indexOf('@');
        long length = parseLong(at >= 0 ? s.substring(0, at) : s, -1);
        long offset = at >= 0 ? parseLong(s.substring(at + 1), -1) : defaultOffset;
        if (length <= 0 || (at >= 0 && offset < 0)) {
            throw new HlsException(HlsException.Reason.MALFORMED, "bad byte range");
        }
        return new HlsPlaylist.ByteRange(length, offset);
    }

    private static SafeUrl resolve(SafeUrl base, String reference) throws HlsException {
        try {
            return base.resolve(reference);
        } catch (UrlRejectedException e) {
            throw new HlsException(HlsException.Reason.BAD_URI, e.reason().name());
        }
    }

    private static String after(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1) : "";
    }

    /** Parses an RFC 8216 attribute list, honoring quoted strings that contain commas. */
    static Map<String, String> attributes(String list) throws HlsException {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        int n = list.length();
        while (i < n) {
            int eq = list.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = list.substring(i, eq).trim();
            int j = eq + 1;
            String value;
            if (j < n && list.charAt(j) == '"') {
                int close = list.indexOf('"', j + 1);
                if (close < 0) {
                    throw new HlsException(HlsException.Reason.MALFORMED, "unterminated quoted attribute");
                }
                value = list.substring(j + 1, close);
                j = close + 1;
            } else {
                int comma = list.indexOf(',', j);
                value = (comma < 0 ? list.substring(j) : list.substring(j, comma)).trim();
                j = comma < 0 ? n : comma;
            }
            if (key.length() > 64 || value.length() > 4096) {
                throw new HlsException(HlsException.Reason.MALFORMED, "attribute too long");
            }
            out.put(key, value);
            if (out.size() > 64) {
                throw new HlsException(HlsException.Reason.MALFORMED, "too many attributes");
            }
            while (j < n && (list.charAt(j) == ',' || list.charAt(j) == ' ')) {
                j++;
            }
            i = j;
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
