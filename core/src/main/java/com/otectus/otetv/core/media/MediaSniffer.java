package com.otectus.otetv.core.media;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bounded content sniffing (section 11.3): MIME types are never trusted alone. Detects HTML error pages, playlists
 * served as "video", and the containers the decoder path supports.
 */
public final class MediaSniffer {
    public enum Kind { WEBM_MATROSKA, MP4, OGG, MPEG_TS, HLS_PLAYLIST, HTML, UNKNOWN }

    public static final int SNIFF_BYTES = 4096;

    private MediaSniffer() {
    }

    public static Kind sniff(byte[] head, int length) {
        int n = Math.min(length, head.length);
        if (n >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            return Kind.WEBM_MATROSKA;
        }
        if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return Kind.MP4;
        }
        if (n >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
            return Kind.OGG;
        }
        if (n >= 189 && head[0] == 0x47 && head[188] == 0x47) {
            return Kind.MPEG_TS;
        }
        String text = new String(head, 0, Math.min(n, 512), StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        if (text.startsWith("ï»¿")) {
            text = text.substring(3);
        }
        if (text.startsWith("#extm3u")) {
            return Kind.HLS_PLAYLIST;
        }
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<?xml") || text.startsWith("<head")
                || text.startsWith("<body") || text.startsWith("{") || text.contains("<html")) {
            return Kind.HTML;
        }
        return Kind.UNKNOWN;
    }
}
