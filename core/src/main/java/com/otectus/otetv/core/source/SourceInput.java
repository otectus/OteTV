package com.otectus.otetv.core.source;

import com.otectus.otetv.core.net.SafeUrl;
import com.otectus.otetv.core.net.UrlRejectedException;

import java.util.Optional;

/**
 * Turns a pasted link into a source descriptor: a recognized YouTube URL becomes a YouTube source, a playlist path
 * ({@code .m3u8}) becomes clear HLS, and any other https URL becomes progressive direct media whose actual codec
 * capability is established later by the worker, never guessed from the extension (section 2.1).
 */
public final class SourceInput {
    private SourceInput() {
    }

    public static MediaSource fromLink(String input, String label, MediaSource.Policy policy) throws SourceException {
        if (input == null || input.isBlank()) {
            throw new SourceException(SourceIssue.EMPTY);
        }
        String trimmed = input.strip();
        String candidate = trimmed.contains("://") ? trimmed : "https://" + trimmed;
        SafeUrl url;
        try {
            url = SafeUrl.parse(candidate);
        } catch (UrlRejectedException e) {
            throw new SourceException(e.reason() == UrlRejectedException.Reason.CREDENTIALS
                    ? SourceIssue.CREDENTIALS_IN_URL : SourceIssue.BAD_URL, e.reason().name());
        }
        Optional<YouTubeRef> yt = YouTubeUrls.fromUrl(url);
        if (yt.isPresent()) {
            return MediaSource.youtube(yt.get(), label);
        }
        SourceType type = url.extension().equals("m3u8") || url.extension().equals("m3u") ? SourceType.HLS_CLEAR : SourceType.HTTPS_VIDEO;
        return MediaSource.network(type, url.toString(), null, label, policy);
    }
}
