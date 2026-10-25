package com.otectus.otetv.core.captions;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.text.TextSanitizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebVTT and SRT sidecar parsing (section 11.5). Markup is stripped and entities decoded, then text is sanitized; cue
 * counts, lengths and timings are bounded and validated. Invalid cues are skipped individually; a file without a
 * single valid cue is an error.
 */
public final class CaptionParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CUES = 10_000;
    public static final int MAX_LINES_PER_CUE = 3;
    public static final int MAX_CHARS_PER_LINE = 160;

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*((?:\\d{1,3}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s+-->\\s+((?:\\d{1,3}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})(?:\\s.*)?$");
    private static final Pattern TAG = Pattern.compile("<[^>]{0,256}>");
    private static final Pattern ASS_OVERRIDE = Pattern.compile("\\{\\\\[^}]{0,128}}");

    public enum Format { WEBVTT, SRT }

    private CaptionParser() {
    }

    public static Format detect(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 16), StandardCharsets.UTF_8).replace("﻿", "");
        return head.startsWith("WEBVTT") ? Format.WEBVTT : Format.SRT;
    }

    public static List<Cue> parse(byte[] data) throws CaptionException {
        if (data.length > MAX_BYTES) {
            throw new CaptionException("caption file too large");
        }
        return parse(new String(data, StandardCharsets.UTF_8), detect(data));
    }

    public static List<Cue> parse(String text, Format format) throws CaptionException {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        if (format == Format.WEBVTT && !body.startsWith("WEBVTT")) {
            throw new CaptionException("missing WEBVTT header");
        }
        String[] blocks = body.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n");
        List<Cue> cues = new ArrayList<>();
        for (int b = 0; b < blocks.length; b++) {
            String block = blocks[b].strip();
            if (block.isEmpty()) {
                continue;
            }
            if (format == Format.WEBVTT && (b == 0 || block.startsWith("NOTE") || block.startsWith("STYLE") || block.startsWith("REGION"))) {
                continue;
            }
            String[] lines = block.split("\n");
            int timingLine = -1;
            for (int i = 0; i < Math.min(lines.length, 2); i++) {
                if (TIMING.matcher(lines[i]).matches()) {
                    timingLine = i;
                    break;
                }
            }
            if (timingLine < 0) {
                continue;
            }
            Matcher m = TIMING.matcher(lines[timingLine]);
            if (!m.matches()) {
                continue;
            }
            long start = parseTime(m.group(1));
            long end = parseTime(m.group(2));
            if (start < 0 || end <= start || end > Limits.MAX_POSITION_MS) {
                continue;
            }
            StringBuilder raw = new StringBuilder();
            for (int i = timingLine + 1; i < lines.length; i++) {
                if (raw.length() > 0) {
                    raw.append('\n');
                }
                raw.append(lines[i]);
            }
            String clean = TextSanitizer.sanitizeLines(decode(raw.toString()), MAX_LINES_PER_CUE, MAX_CHARS_PER_LINE);
            if (clean.isEmpty()) {
                continue;
            }
            cues.add(new Cue(start, end, clean));
            if (cues.size() >= MAX_CUES) {
                break;
            }
        }
        if (cues.isEmpty()) {
            throw new CaptionException("no valid cues");
        }
        cues.sort(Comparator.comparingLong(Cue::startMs));
        return List.copyOf(cues);
    }

    static long parseTime(String s) {
        String t = s.replace(',', '.');
        String[] parts = t.split(":");
        try {
            long h = 0;
            long m;
            String secPart;
            if (parts.length == 3) {
                h = Long.parseLong(parts[0]);
                m = Long.parseLong(parts[1]);
                secPart = parts[2];
            } else if (parts.length == 2) {
                m = Long.parseLong(parts[0]);
                secPart = parts[1];
            } else {
                return -1;
            }
            String[] sm = secPart.split("\\.");
            long sec = Long.parseLong(sm[0]);
            String fracText = sm.length > 1 ? sm[1] : "0";
            while (fracText.length() < 3) {
                fracText = fracText + "0";
            }
            long ms = Long.parseLong(fracText.substring(0, 3));
            if (m >= 60 || sec >= 60 || h > 48) {
                return -1;
            }
            return ((h * 60 + m) * 60 + sec) * 1000 + ms;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String decode(String s) {
        String out = TAG.matcher(s).replaceAll("");
        out = ASS_OVERRIDE.matcher(out).replaceAll("");
        return out.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "")
                .replace("&rlm;", "").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /** The cue active at a position, using binary search over cues sorted by start time. */
    public static Cue activeCue(List<Cue> cues, long positionMs) {
        int lo = 0;
        int hi = cues.size() - 1;
        int candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs() <= positionMs) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        for (int i = candidate; i >= 0 && i > candidate - 4; i--) {
            if (cues.get(i).activeAt(positionMs)) {
                return cues.get(i);
            }
        }
        return null;
    }

    public static final class CaptionException extends Exception {
        public CaptionException(String message) {
            super(message);
        }
    }
}
