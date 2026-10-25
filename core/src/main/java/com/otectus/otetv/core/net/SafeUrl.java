package com.otectus.otetv.core.net;

import com.otectus.otetv.core.Limits;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A strictly parsed, normalized http(s) URL. Construction is the validation: user info is rejected, hosts are
 * converted to lowercase ASCII (IDNA), ambiguous numeric host spellings such as {@code 0x7f.1} or
 * {@code 2130706433} are refused, fragments are dropped and non-ASCII path characters are percent-encoded.
 * Destination safety (which addresses a host may resolve to) is a separate decision: see {@link DestinationPolicy}.
 */
public final class SafeUrl {
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern CANONICAL_IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private final String scheme;
    private final String host;
    private final int port;
    private final boolean explicitPort;
    private final String path;
    private final String query;
    private final boolean ipLiteral;

    private SafeUrl(String scheme, String host, int port, boolean explicitPort, String path, String query, boolean ipLiteral) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.explicitPort = explicitPort;
        this.path = path;
        this.query = query;
        this.ipLiteral = ipLiteral;
    }

    public static SafeUrl parse(String input) throws UrlRejectedException {
        if (input == null || input.isBlank()) {
            throw new UrlRejectedException(UrlRejectedException.Reason.EMPTY, null);
        }
        String trimmed = input.strip();
        if (trimmed.length() > Limits.MAX_URL_CHARS) {
            throw new UrlRejectedException(UrlRejectedException.Reason.TOO_LONG, null);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c == 0x7F || c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new UrlRejectedException(UrlRejectedException.Reason.ILLEGAL_CHARACTER, "position " + i);
            }
        }
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            throw new UrlRejectedException(UrlRejectedException.Reason.MALFORMED, "missing scheme");
        }
        String scheme = trimmed.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new UrlRejectedException(UrlRejectedException.Reason.UNSUPPORTED_SCHEME, scheme);
        }
        String afterScheme = trimmed.substring(schemeEnd + 3);
        int authorityEnd = indexOfAny(afterScheme, "/?#");
        String authority = authorityEnd < 0 ? afterScheme : afterScheme.substring(0, authorityEnd);
        String remainder = authorityEnd < 0 ? "" : afterScheme.substring(authorityEnd);
        if (authority.indexOf('@') >= 0) {
            throw new UrlRejectedException(UrlRejectedException.Reason.CREDENTIALS, null);
        }
        if (authority.isEmpty()) {
            throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "empty");
        }

        String hostPart;
        String portPart = null;
        boolean literal6 = false;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "unterminated IPv6 literal");
            }
            hostPart = authority.substring(1, close);
            String tail = authority.substring(close + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "junk after IPv6 literal");
                }
                portPart = tail.substring(1);
            }
            literal6 = true;
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                hostPart = authority.substring(0, colon);
                portPart = authority.substring(colon + 1);
            } else {
                hostPart = authority;
            }
        }

        int defaultPort = scheme.equals("https") ? 443 : 80;
        int port = defaultPort;
        boolean explicitPort = false;
        if (portPart != null) {
            if (portPart.isEmpty() || portPart.length() > 5 || !portPart.chars().allMatch(Character::isDigit)) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_PORT, portPart);
            }
            port = Integer.parseInt(portPart);
            if (port < 1 || port > 65535) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_PORT, portPart);
            }
            explicitPort = port != defaultPort;
        }

        String host;
        boolean ipLiteral;
        if (literal6) {
            if (hostPart.indexOf('%') >= 0) {
                throw new UrlRejectedException(UrlRejectedException.Reason.ZONE_ID, null);
            }
            if (hostPart.indexOf(':') < 0 || !hostPart.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.')) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "invalid IPv6 literal");
            }
            try {
                InetAddress parsed = InetAddress.getByName(hostPart);
                byte[] raw = parsed.getAddress();
                host = raw.length == 16 ? compressIpv6(raw) : parsed.getHostAddress();
            } catch (UnknownHostException e) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "invalid IPv6 literal");
            }
            ipLiteral = true;
        } else {
            if (hostPart.indexOf('%') >= 0) {
                throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "percent-encoded host");
            }
            String lower = hostPart.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".")) {
                lower = lower.substring(0, lower.length() - 1);
            }
            // inet_aton accepts 127.1, 0177.0.0.1, 0x7f.1 and 2130706433 as loopback. Only the canonical dotted
            // quad is accepted as an IPv4 literal; every other all-numeric spelling is refused outright.
            if (!CANONICAL_IPV4.matcher(lower).matches() && looksNumeric(lower)) {
                throw new UrlRejectedException(UrlRejectedException.Reason.AMBIGUOUS_NUMERIC_HOST, lower);
            }
            if (CANONICAL_IPV4.matcher(lower).matches()) {
                host = lower;
                ipLiteral = true;
            } else {
                String ascii;
                try {
                    ascii = IDN.toASCII(lower, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                } catch (IllegalArgumentException e) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "invalid internationalized name");
                }
                if (ascii.isEmpty() || ascii.length() > 253) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "length");
                }
                String[] labels = ascii.split("\\.", -1);
                for (String label : labels) {
                    if (!LABEL.matcher(label).matches()) {
                        throw new UrlRejectedException(UrlRejectedException.Reason.BAD_HOST, "label");
                    }
                }
                if (labels.length < 2) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.SINGLE_LABEL_HOST, ascii);
                }
                if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.AMBIGUOUS_NUMERIC_HOST, ascii);
                }
                host = ascii;
                ipLiteral = false;
            }
        }

        int fragment = remainder.indexOf('#');
        if (fragment >= 0) {
            remainder = remainder.substring(0, fragment);
        }
        String rawPath = remainder;
        String rawQuery = null;
        int q = remainder.indexOf('?');
        if (q >= 0) {
            rawPath = remainder.substring(0, q);
            rawQuery = remainder.substring(q + 1);
        }
        String path = normalizePath(encodeNonAscii(rawPath));
        String query = rawQuery == null ? null : encodeNonAscii(rawQuery);
        validatePercentEncoding(path);
        if (query != null) {
            validatePercentEncoding(query);
        }
        SafeUrl url = new SafeUrl(scheme, host, port, explicitPort, path, query, ipLiteral);
        if (url.toString().length() > Limits.MAX_URL_CHARS) {
            throw new UrlRejectedException(UrlRejectedException.Reason.TOO_LONG, null);
        }
        return url;
    }

    /** RFC 5952 canonical text form: lowercase, no leading zeros, longest zero run (length 2 or more) as "::". */
    static String compressIpv6(byte[] a) {
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) {
            words[i] = ((a[2 * i] & 0xFF) << 8) | (a[2 * i + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (words[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && words[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(words[i]));
        }
        return sb.toString();
    }

    private static boolean looksNumeric(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty()) {
                return false;
            }
            if (label.startsWith("0x")) {
                if (!label.substring(2).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                    return false;
                }
            } else if (!label.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private static String encodeNonAscii(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 16);
                    out.append(s, 0, i);
                }
                int cp = s.codePointAt(i);
                if (Character.charCount(cp) == 2) {
                    i++;
                }
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    private static void validatePercentEncoding(String s) throws UrlRejectedException {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '%') {
                if (i + 2 >= s.length() || Character.digit(s.charAt(i + 1), 16) < 0 || Character.digit(s.charAt(i + 2), 16) < 0) {
                    throw new UrlRejectedException(UrlRejectedException.Reason.MALFORMED, "bad percent-encoding");
                }
            }
        }
    }

    private static String normalizePath(String rawPath) throws UrlRejectedException {
        if (rawPath.isEmpty()) {
            return "/";
        }
        try {
            // The single-argument constructor parses strictly and rejects characters that are illegal in a path.
            String normalized = new URI("http://h" + rawPath).normalize().getRawPath();
            if (normalized == null || normalized.isEmpty()) {
                return "/";
            }
            while (normalized.startsWith("/../")) {
                normalized = normalized.substring(3);
            }
            return normalized.startsWith("/") ? normalized : "/" + normalized;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new UrlRejectedException(UrlRejectedException.Reason.MALFORMED, "path");
        }
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Resolves a relative or absolute reference (HLS entries, redirect targets) and validates the result. */
    public SafeUrl resolve(String reference) throws UrlRejectedException {
        if (reference == null || reference.isBlank()) {
            throw new UrlRejectedException(UrlRejectedException.Reason.EMPTY, null);
        }
        String ref = reference.strip();
        if (ref.length() > Limits.MAX_URL_CHARS) {
            throw new UrlRejectedException(UrlRejectedException.Reason.TOO_LONG, null);
        }
        try {
            URI base = new URI(toString());
            URI resolved = base.resolve(new URI(encodeNonAscii(ref)));
            return parse(resolved.toString());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new UrlRejectedException(UrlRejectedException.Reason.MALFORMED, "reference");
        }
    }

    public String scheme() {
        return scheme;
    }

    public boolean isHttps() {
        return scheme.equals("https");
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean isIpLiteral() {
        return ipLiteral;
    }

    public String path() {
        return path;
    }

    public String query() {
        return query;
    }

    public String pathAndQuery() {
        return query == null ? path : path + "?" + query;
    }

    /** The {@code Host} header value. */
    public String hostHeader() {
        String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return explicitPort ? h + ":" + port : h;
    }

    /** The lowercase file extension of the last path segment, or an empty string. */
    public String extension() {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public SafeUrl withScheme(String newScheme) throws UrlRejectedException {
        return parse(newScheme + "://" + hostHeader() + pathAndQuery());
    }

    @Override
    public String toString() {
        return scheme + "://" + hostHeader() + pathAndQuery();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SafeUrl other && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(toString());
    }
}
