package com.otectus.otetv.core.redact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials, query strings, tokens, local paths and private media names from text bound for logs,
 * crash exports and the copyable diagnostics panel (section 16.4). Redaction is applied even with debug logging on.
 */
public final class Redactor {
    private static final Pattern URL = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,15})://([^\\s\"'<>]+)");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_\\-+/=]{32,}\\b");
    private static final Pattern WINDOWS_PROFILE = Pattern.compile("(?i)\\b[A-Z]:\\\\(Users|Documents and Settings)\\\\[^\\\\\\s\"']+");
    private static final Pattern UNIX_HOME = Pattern.compile("(/home/|/Users/|/var/home/)[^/\\s\"']+");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("(?i)\\b[A-Z]:\\\\[^\\s\"']+");
    private static final Pattern UNIX_ABSOLUTE_FILE = Pattern.compile("(?<![\\w:/])/(?:[^/\\s\"']+/)+([^/\\s\"']+\\.[A-Za-z0-9]{2,5})\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization|cookie|set-cookie|bearer|token|key|sig|signature|password|passwd|secret)([=:\\s]+)([^\\s&;,\"']+)");

    private Redactor() {
    }

    /** Keeps scheme, host and port plus the media extension; drops user info, path, query and fragment. */
    public static String redactUrl(String url) {
        if (url == null) {
            return "";
        }
        Matcher m = URL.matcher(url.trim());
        if (!m.matches()) {
            return "<redacted>";
        }
        String scheme = m.group(1).toLowerCase();
        String rest = m.group(2);
        int slash = indexOfAny(rest, "/?#");
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        StringBuilder out = new StringBuilder(scheme).append("://").append(authority);
        if (slash >= 0) {
            String pathPart = rest.substring(slash);
            int q = indexOfAny(pathPart, "?#");
            String path = q < 0 ? pathPart : pathPart.substring(0, q);
            int dot = path.lastIndexOf('.');
            int lastSlash = path.lastIndexOf('/');
            if (path.length() > 1) {
                out.append("/\u2026");
                if (dot > lastSlash && path.length() - dot <= 6) {
                    out.append(path.substring(dot));
                }
            }
            if (q >= 0) {
                out.append("?<redacted>");
            }
        }
        return out.toString();
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = replaceAll(URL, text, m -> redactUrl(m.group()));
        out = replaceAll(BEARER, out, m -> m.group(1) + m.group(2) + "<redacted>");
        out = replaceAll(WINDOWS_PROFILE, out, m -> "%USERPROFILE%");
        out = replaceAll(UNIX_HOME, out, m -> "~");
        out = replaceAll(WINDOWS_ABSOLUTE, out, m -> "<path>");
        out = replaceAll(UNIX_ABSOLUTE_FILE, out, m -> "<path>" + extensionOf(m.group(1)));
        out = replaceAll(LONG_TOKEN, out, m -> "<redacted>");
        return out;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? "/*" + name.substring(dot) : "";
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern pattern, String input, Replacer replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
