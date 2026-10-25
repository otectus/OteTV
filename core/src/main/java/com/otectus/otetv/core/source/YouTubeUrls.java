package com.otectus.otetv.core.source;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the documented YouTube watch, short-link, embed, shorts and live URL forms and reduces them to a
 * {@link YouTubeRef}. Hosts are matched exactly; anything merely containing "youtube" is not trusted. Playlist
 * parameters are ignored: one video per source (section 12.3).
 */
public final class YouTubeUrls {
    private static final Set<String> WATCH_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com");
    private static final Set<String> EMBED_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
            "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final String SHORT_HOST = "youtu.be";
    private static final Pattern HMS = Pattern.compile("(?:(\\d{1,2})h)?(?:(\\d{1,4})m)?(?:(\\d{1,6})s?)?");

    private YouTubeUrls() {
    }

    public static boolean isYouTubeHost(String host) {
        return WATCH_HOSTS.contains(host) || EMBED_HOSTS.contains(host) || SHORT_HOST.equals(host);
    }

    /**
     * Parses user input: a supported URL, or a bare 11-character video id when {@code allowBareId} is set.
     */
    public static YouTubeRef parse(String input, boolean allowBareId) throws SourceException {
        if (input == null || input.isBlank()) {
            throw new SourceException(SourceIssue.EMPTY);
        }
        String trimmed = input.strip();
        if (allowBareId && YouTubeRef.VIDEO_ID.matcher(trimmed).matches()) {
            return new YouTubeRef(trimmed, 0);
        }
        String candidate = trimmed;
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        SafeUrl url;
        try {
            url = SafeUrl.parse(candidate);
        } catch (UrlRejectedException e) {
            throw new SourceException(e.reason() == UrlRejectedException.Reason.CREDENTIALS
                    ? SourceIssue.CREDENTIALS_IN_URL : SourceIssue.BAD_URL, e.reason().name());
        }
        return fromUrl(url).orElseThrow(() -> new SourceException(
                isYouTubeHost(url.host()) ? SourceIssue.UNSUPPORTED_YOUTUBE_FORM : SourceIssue.NOT_A_YOUTUBE_URL));
    }

    public static Optional<YouTubeRef> fromUrl(SafeUrl url) throws SourceException {
        String host = url.host();
        if (!isYouTubeHost(host)) {
            if (host.contains("youtube") || host.contains("youtu.be")) {
                throw new SourceException(SourceIssue.LOOKALIKE_HOST, host);
            }
            return Optional.empty();
        }
        if (url.port() != 443 && url.port() != 80) {
            throw new SourceException(SourceIssue.LOOKALIKE_HOST, "port");
        }
        String path = url.path();
        String id = null;
        if (SHORT_HOST.equals(host)) {
            id = firstSegment(path);
        } else if (path.equals("/watch") && WATCH_HOSTS.contains(host)) {
            id = queryParam(url.query(), "v");
        } else if (EMBED_HOSTS.contains(host)) {
            for (String prefix : new String[]{"/embed/", "/shorts/", "/live/", "/v/"}) {
                if (path.startsWith(prefix)) {
                    if (prefix.equals("/embed/") || EMBED_HOSTS.contains(host)) {
                        id = firstSegment(path.substring(prefix.length() - 1));
                    }
                    break;
                }
            }
        }
        if (id == null) {
            return Optional.empty();
        }
        if (!YouTubeRef.VIDEO_ID.matcher(id).matches()) {
            throw new SourceException(SourceIssue.BAD_VIDEO_ID);
        }
        int start = 0;
        String t = queryParam(url.query(), "t");
        if (t == null) {
            t = queryParam(url.query(), "start");
        }
        if (t != null) {
            start = parseStart(t);
        }
        return Optional.of(new YouTubeRef(id, start));
    }

    static int parseStart(String value) throws SourceException {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty() || v.length() > 16) {
            throw new SourceException(SourceIssue.BAD_START_TIME);
        }
        long seconds;
        if (v.chars().allMatch(Character::isDigit)) {
            seconds = Long.parseLong(v);
        } else {
            Matcher m = HMS.matcher(v);
            if (!m.matches()) {
                throw new SourceException(SourceIssue.BAD_START_TIME);
            }
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
            long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
            seconds = h * 3600 + min * 60 + s;
        }
        if (seconds < 0 || seconds > Limits.MAX_START_SECONDS) {
            throw new SourceException(SourceIssue.BAD_START_TIME);
        }
        return (int) seconds;
    }

    private static String firstSegment(String path) {
        if (path == null || path.length() < 2) {
            return null;
        }
        String p = path.substring(1);
        int slash = p.indexOf('/');
        return slash < 0 ? p : p.substring(0, slash);
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String raw = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
