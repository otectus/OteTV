package com.otectus.otetv.core.source;

import com.otectus.otetv.core.Limits;
import com.otectus.otetv.core.net.AddressCategory;
import com.otectus.otetv.core.net.IpPolicy;
import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;
import com.otectus.otetv.core.text.TextSanitizer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An immutable, validated description of what a TV should play (section 8.1): provider, public content identity,
 * a safe display label and transport class. Every construction path, including saves, packets and channel JSON,
 * goes through {@link #fromFields}, so a hostile server, datapack or client cannot smuggle an unvalidated value.
 * Local filesystem paths are never part of a shareable source.
 */
public final class MediaSource {
    public static final Pattern ASSET_ID = Pattern.compile("[a-z0-9_.-]{1,48}:[a-z0-9_./-]{1,79}");
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public static final Pattern LOCAL_KEY = Pattern.compile("[0-9a-f]{16,64}");

    public static final String F_LABEL = "label";
    public static final String F_URL = "url";
    public static final String F_CAPTIONS = "captions";
    public static final String F_VIDEO_ID = "videoId";
    public static final String F_START = "start";
    public static final String F_ASSET = "asset";
    public static final String F_SHA256 = "sha256";
    public static final String F_DURATION = "durationMs";
    public static final String F_LOCAL_KEY = "localKey";
    public static final int MAX_FIELDS = 8;
    public static final int MAX_FIELD_KEY = 16;

    /** How strictly to validate. Servers may permit private-network channel URLs; clients still need a LAN grant. */
    public record Policy(boolean allowHttp, boolean allowNonPublicLiterals) {
        public static final Policy STRICT = new Policy(false, false);
        /**
         * Structure only: accepts http and private literals that a server may have approved. Used when reading saves
         * and packets; the current policy is applied again at playback time, and clients still need a LAN grant.
         */
        public static final Policy STRUCTURAL = new Policy(true, true);
    }

    private final SourceType type;
    private final String label;
    private final SafeUrl url;
    private final SafeUrl captionsUrl;
    private final YouTubeRef youtube;
    private final String assetId;
    private final String sha256;
    private final long durationMs;
    private final String localKey;

    private MediaSource(SourceType type, String label, SafeUrl url, SafeUrl captionsUrl, YouTubeRef youtube,
                        String assetId, String sha256, long durationMs, String localKey) {
        this.type = type;
        this.label = label;
        this.url = url;
        this.captionsUrl = captionsUrl;
        this.youtube = youtube;
        this.assetId = assetId;
        this.sha256 = sha256;
        this.durationMs = durationMs;
        this.localKey = localKey;
    }

    public static MediaSource bundled(String assetId, String label) throws SourceException {
        return fromFields(SourceType.BUNDLED, Map.of(F_ASSET, assetId, F_LABEL, label), Policy.STRICT);
    }

    public static MediaSource youtube(YouTubeRef ref, String label) throws SourceException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(F_VIDEO_ID, ref.videoId());
        f.put(F_START, Integer.toString(ref.startSeconds()));
        f.put(F_LABEL, label == null ? "" : label);
        return fromFields(SourceType.YOUTUBE, f, Policy.STRICT);
    }

    public static MediaSource network(SourceType type, String url, String captionsUrl, String label, Policy policy) throws SourceException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(F_URL, url);
        if (captionsUrl != null && !captionsUrl.isBlank()) {
            f.put(F_CAPTIONS, captionsUrl);
        }
        f.put(F_LABEL, label == null ? "" : label);
        return fromFields(type, f, policy);
    }

    public static MediaSource sharedAsset(String assetId, String sha256, long durationMs, String label) throws SourceException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(F_ASSET, assetId);
        f.put(F_SHA256, sha256);
        f.put(F_DURATION, Long.toString(durationMs));
        f.put(F_LABEL, label == null ? "" : label);
        return fromFields(SourceType.SHARED_LOCAL_ASSET, f, Policy.STRICT);
    }

    public static MediaSource localFile(String localKey, String label) throws SourceException {
        return fromFields(SourceType.LOCAL_FILE, Map.of(F_LOCAL_KEY, localKey, F_LABEL, label == null ? "" : label), Policy.STRICT);
    }

    /** The single validating constructor. Unknown keys are rejected rather than ignored. */
    public static MediaSource fromFields(SourceType type, Map<String, String> fields, Policy policy) throws SourceException {
        Objects.requireNonNull(type, "type");
        if (fields.size() > MAX_FIELDS) {
            throw new SourceException(SourceIssue.TOO_LONG, "too many fields");
        }
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey() == null || e.getKey().length() > MAX_FIELD_KEY || e.getValue() == null
                    || e.getValue().length() > Limits.MAX_URL_CHARS) {
                throw new SourceException(SourceIssue.TOO_LONG, "field");
            }
        }
        String rawLabel = fields.getOrDefault(F_LABEL, "");
        String label = TextSanitizer.sanitize(rawLabel, Limits.MAX_LABEL);
        if (rawLabel.length() > Limits.MAX_LABEL * 4) {
            throw new SourceException(SourceIssue.BAD_LABEL);
        }
        switch (type) {
            case BUNDLED -> {
                requireOnly(fields, F_ASSET, F_LABEL);
                String asset = requireAsset(fields.get(F_ASSET));
                return new MediaSource(type, label, null, null, null, asset, null, -1, null);
            }
            case HTTPS_VIDEO, HLS_CLEAR -> {
                requireOnly(fields, F_URL, F_CAPTIONS, F_LABEL);
                SafeUrl url = parseNetworkUrl(fields.get(F_URL), policy, SourceIssue.BAD_URL);
                if (YouTubeUrls.isYouTubeHost(url.host())) {
                    throw new SourceException(SourceIssue.NOT_A_YOUTUBE_URL, "use the youtube source type");
                }
                SafeUrl captions = null;
                String c = fields.get(F_CAPTIONS);
                if (c != null && !c.isEmpty()) {
                    captions = parseNetworkUrl(c, policy, SourceIssue.BAD_CAPTIONS_URL);
                }
                return new MediaSource(type, label, url, captions, null, null, null, -1, null);
            }
            case YOUTUBE -> {
                requireOnly(fields, F_VIDEO_ID, F_START, F_LABEL);
                String id = fields.get(F_VIDEO_ID);
                if (id == null || !YouTubeRef.VIDEO_ID.matcher(id).matches()) {
                    throw new SourceException(SourceIssue.BAD_VIDEO_ID);
                }
                int start = 0;
                String s = fields.get(F_START);
                if (s != null && !s.isEmpty()) {
                    start = YouTubeUrls.parseStart(s);
                }
                return new MediaSource(type, label, null, null, new YouTubeRef(id, start), null, null, -1, null);
            }
            case SHARED_LOCAL_ASSET -> {
                requireOnly(fields, F_ASSET, F_SHA256, F_DURATION, F_LABEL);
                String asset = requireAsset(fields.get(F_ASSET));
                String digest = fields.get(F_SHA256);
                if (digest == null || !SHA256.matcher(digest).matches()) {
                    throw new SourceException(SourceIssue.BAD_DIGEST);
                }
                long duration = -1;
                String d = fields.get(F_DURATION);
                if (d != null && !d.isEmpty()) {
                    try {
                        duration = Long.parseLong(d);
                    } catch (NumberFormatException e) {
                        throw new SourceException(SourceIssue.BAD_DURATION);
                    }
                    if (duration < -1 || duration > Limits.MAX_POSITION_MS) {
                        throw new SourceException(SourceIssue.BAD_DURATION);
                    }
                }
                return new MediaSource(type, label, null, null, null, asset, digest, duration, null);
            }
            case LOCAL_FILE -> {
                requireOnly(fields, F_LOCAL_KEY, F_LABEL);
                String key = fields.get(F_LOCAL_KEY);
                if (key == null || !LOCAL_KEY.matcher(key).matches()) {
                    throw new SourceException(SourceIssue.BAD_ASSET_ID, "local key");
                }
                return new MediaSource(type, label, null, null, null, null, null, -1, key);
            }
            default -> throw new SourceException(SourceIssue.UNKNOWN_TYPE);
        }
    }

    private static void requireOnly(Map<String, String> fields, String... allowed) throws SourceException {
        outer:
        for (String key : fields.keySet()) {
            for (String a : allowed) {
                if (a.equals(key)) {
                    continue outer;
                }
            }
            throw new SourceException(SourceIssue.UNKNOWN_TYPE, "unexpected field " + key);
        }
    }

    private static String requireAsset(String value) throws SourceException {
        if (value == null || value.length() > Limits.MAX_ASSET_ID || !ASSET_ID.matcher(value).matches() || value.contains("..")) {
            throw new SourceException(SourceIssue.BAD_ASSET_ID);
        }
        return value;
    }

    private static SafeUrl parseNetworkUrl(String value, Policy policy, SourceIssue badIssue) throws SourceException {
        SafeUrl url;
        try {
            url = SafeUrl.parse(value);
        } catch (UrlRejectedException e) {
            throw new SourceException(e.reason() == UrlRejectedException.Reason.CREDENTIALS
                    ? SourceIssue.CREDENTIALS_IN_URL : badIssue, e.reason().name());
        }
        if (!url.isHttps() && !policy.allowHttp()) {
            throw new SourceException(SourceIssue.NOT_HTTPS);
        }
        if (url.isIpLiteral() && !policy.allowNonPublicLiterals()) {
            try {
                AddressCategory category = IpPolicy.classify(InetAddress.getByName(url.host()));
                if (category != AddressCategory.PUBLIC) {
                    throw new SourceException(SourceIssue.NON_PUBLIC_HOST, category.name());
                }
            } catch (UnknownHostException e) {
                throw new SourceException(badIssue, "literal");
            }
        }
        return url;
    }

    /** The canonical field map; the inverse of {@link #fromFields}. */
    public Map<String, String> toFields() {
        Map<String, String> f = new LinkedHashMap<>();
        switch (type) {
            case BUNDLED -> f.put(F_ASSET, assetId);
            case HTTPS_VIDEO, HLS_CLEAR -> {
                f.put(F_URL, url.toString());
                if (captionsUrl != null) {
                    f.put(F_CAPTIONS, captionsUrl.toString());
                }
            }
            case YOUTUBE -> {
                f.put(F_VIDEO_ID, youtube.videoId());
                if (youtube.startSeconds() > 0) {
                    f.put(F_START, Integer.toString(youtube.startSeconds()));
                }
            }
            case SHARED_LOCAL_ASSET -> {
                f.put(F_ASSET, assetId);
                f.put(F_SHA256, sha256);
                if (durationMs >= 0) {
                    f.put(F_DURATION, Long.toString(durationMs));
                }
            }
            case LOCAL_FILE -> f.put(F_LOCAL_KEY, localKey);
        }
        if (!label.isEmpty()) {
            f.put(F_LABEL, label);
        }
        return Collections.unmodifiableMap(f);
    }

    public SourceType type() {
        return type;
    }

    public String label() {
        return label;
    }

    /** A label suitable for display: the provided label or a safe fallback that never reveals a query string. */
    public String displayLabel() {
        if (!label.isEmpty()) {
            return label;
        }
        return switch (type) {
            case BUNDLED -> assetId;
            case HTTPS_VIDEO, HLS_CLEAR -> url.host();
            case YOUTUBE -> "YouTube " + youtube.videoId();
            case SHARED_LOCAL_ASSET -> assetId;
            case LOCAL_FILE -> "Local file";
        };
    }

    public Optional<SafeUrl> url() {
        return Optional.ofNullable(url);
    }

    public Optional<SafeUrl> captionsUrl() {
        return Optional.ofNullable(captionsUrl);
    }

    public Optional<YouTubeRef> youtube() {
        return Optional.ofNullable(youtube);
    }

    public Optional<String> assetId() {
        return Optional.ofNullable(assetId);
    }

    public Optional<String> sha256() {
        return Optional.ofNullable(sha256);
    }

    public long declaredDurationMs() {
        return durationMs;
    }

    public Optional<String> localKey() {
        return Optional.ofNullable(localKey);
    }

    public TransportClass transport() {
        return type.transport();
    }

    /** Provider id used for consent scoping and capability policy. */
    public String provider() {
        return switch (type) {
            case BUNDLED -> "otetv";
            case HTTPS_VIDEO -> "https";
            case HLS_CLEAR -> "hls";
            case YOUTUBE -> "youtube";
            case SHARED_LOCAL_ASSET -> "shared_asset";
            case LOCAL_FILE -> "local";
        };
    }

    /**
     * The host or service a client contacts for this source, for consent prompts. YouTube sources name the service.
     */
    public Optional<String> networkHost() {
        return switch (type) {
            case HTTPS_VIDEO, HLS_CLEAR -> Optional.of(url.host());
            case YOUTUBE -> Optional.of("youtube.com");
            default -> Optional.empty();
        };
    }

    /** Stable identity of the content, independent of label. */
    public String contentIdentity() {
        return switch (type) {
            case BUNDLED -> "bundled:" + assetId;
            case HTTPS_VIDEO -> "https:" + url;
            case HLS_CLEAR -> "hls:" + url;
            case YOUTUBE -> "youtube:" + youtube.videoId();
            case SHARED_LOCAL_ASSET -> "shared:" + sha256;
            case LOCAL_FILE -> "local:" + localKey;
        };
    }

    public boolean isShareable() {
        return type.isShareable();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MediaSource other && type == other.type && toFields().equals(other.toFields());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, toFields());
    }

    @Override
    public String toString() {
        return "MediaSource[" + type.id() + " " + displayLabel() + "]";
    }
}
