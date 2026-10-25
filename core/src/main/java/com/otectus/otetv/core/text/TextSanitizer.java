package com.otectus.otetv.core.text;

import java.text.Normalizer;

/**
 * Normalizes untrusted display text: NFC, no control or format characters (which removes bidi overrides and
 * zero-width tricks), no Minecraft legacy formatting codes, collapsed whitespace, and a code-point limit that never
 * splits a surrogate pair.
 */
public final class TextSanitizer {
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int SECTION_SIGN = 0x00A7;

    private TextSanitizer() {
    }

    public static String sanitize(String input, int maxCodePoints) {
        if (input == null || maxCodePoints <= 0) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(Math.min(normalized.length(), maxCodePoints * 2));
        int count = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < normalized.length() && count < maxCodePoints; ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isDisallowed(cp)) {
                continue;
            }
            if (pendingSpace) {
                if (count + 1 >= maxCodePoints) {
                    break;
                }
                out.append(' ');
                count++;
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
            count++;
        }
        return out.toString();
    }

    /** Multi-line variant for captions: each line is sanitized and blank lines are dropped. */
    public static String sanitizeLines(String input, int maxLines, int maxCodePointsPerLine) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (String line : input.split("\\R")) {
            String clean = sanitize(line, maxCodePointsPerLine);
            if (clean.isEmpty()) {
                continue;
            }
            if (lines > 0) {
                out.append('\n');
            }
            out.append(clean);
            if (++lines >= maxLines) {
                break;
            }
        }
        return out.toString();
    }

    public static boolean isDisallowed(int cp) {
        if (cp == SECTION_SIGN) {
            return true;
        }
        int type = Character.getType(cp);
        if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
            return cp != ZERO_WIDTH_JOINER;
        }
        return false;
    }
}
