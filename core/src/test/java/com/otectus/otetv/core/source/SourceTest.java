package com.otectus.otetv.core.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceTest {
    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s, dQw4w9WgXcQ, 90",
            "https://m.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "https://youtu.be/dQw4w9WgXcQ?t=42, dQw4w9WgXcQ, 42",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?start=10, dQw4w9WgXcQ, 10",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "https://www.youtube.com/live/dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "youtu.be/dQw4w9WgXcQ, dQw4w9WgXcQ, 0",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s, dQw4w9WgXcQ, 3723",
    })
    void recognizesDocumentedYouTubeForms(String input, String id, int start) throws Exception {
        YouTubeRef ref = YouTubeUrls.parse(input, false);
        assertEquals(id, ref.videoId());
        assertEquals(start, ref.startSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "https://evil-youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be.example.org/dQw4w9WgXcQ", "https://www.youtube.co/watch?v=dQw4w9WgXcQ"})
    void rejectsLookalikeHosts(String input) {
        SourceException e = assertThrows(SourceException.class, () -> YouTubeUrls.parse(input, false));
        assertEquals(true, e.issue() == SourceIssue.LOOKALIKE_HOST || e.issue() == SourceIssue.NOT_A_YOUTUBE_URL);
    }

    @Test
    void rejectsBadIdsUnsupportedFormsAndHugeStarts() {
        assertEquals(SourceIssue.BAD_VIDEO_ID, assertThrows(SourceException.class,
                () -> YouTubeUrls.parse("https://www.youtube.com/watch?v=short", false)).issue());
        assertEquals(SourceIssue.UNSUPPORTED_YOUTUBE_FORM, assertThrows(SourceException.class,
                () -> YouTubeUrls.parse("https://www.youtube.com/channel/UC123", false)).issue());
        assertEquals(SourceIssue.BAD_START_TIME, assertThrows(SourceException.class,
                () -> YouTubeUrls.parse("https://youtu.be/dQw4w9WgXcQ?t=99999999", false)).issue());
        assertEquals(SourceIssue.CREDENTIALS_IN_URL, assertThrows(SourceException.class,
                () -> YouTubeUrls.parse("https://u:p@www.youtube.com/watch?v=dQw4w9WgXcQ", false)).issue());
    }

    @Test
    void bareIdsOnlyWhenAllowed() throws Exception {
        assertEquals("dQw4w9WgXcQ", YouTubeUrls.parse("dQw4w9WgXcQ", true).videoId());
        assertThrows(SourceException.class, () -> YouTubeUrls.parse("dQw4w9WgXcQ", false));
    }

    @Test
    void fieldRoundTripIsStable() throws Exception {
        MediaSource a = MediaSource.network(SourceType.HTTPS_VIDEO, "https://Media.Example.com/clip.webm",
                "https://media.example.com/clip.vtt", "Clip ‮evil", MediaSource.Policy.STRICT);
        MediaSource b = MediaSource.fromFields(a.type(), a.toFields(), MediaSource.Policy.STRICT);
        assertEquals(a, b);
        assertEquals("Clip evil", a.label());
        assertEquals("media.example.com", a.networkHost().orElseThrow());
    }

    @Test
    void rejectsUnknownFieldsPlainHttpAndPrivateLiterals() {
        Map<String, String> f = new HashMap<>();
        f.put(MediaSource.F_URL, "https://media.example.com/a.webm");
        f.put("command", "rm -rf");
        assertThrows(SourceException.class, () -> MediaSource.fromFields(SourceType.HTTPS_VIDEO, f, MediaSource.Policy.STRICT));
        assertEquals(SourceIssue.NOT_HTTPS, assertThrows(SourceException.class, () -> MediaSource.network(SourceType.HTTPS_VIDEO,
                "http://media.example.com/a.webm", null, "", MediaSource.Policy.STRICT)).issue());
        assertEquals(SourceIssue.NON_PUBLIC_HOST, assertThrows(SourceException.class, () -> MediaSource.network(SourceType.HTTPS_VIDEO,
                "https://192.168.1.4/a.webm", null, "", MediaSource.Policy.STRICT)).issue());
        assertEquals(SourceIssue.NOT_A_YOUTUBE_URL, assertThrows(SourceException.class, () -> MediaSource.network(SourceType.HTTPS_VIDEO,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, "", MediaSource.Policy.STRICT)).issue());
    }

    @Test
    void permissivePolicyAllowsLanChannelsForServersThatOptIn() throws Exception {
        MediaSource s = MediaSource.network(SourceType.HTTPS_VIDEO, "http://192.168.1.4:8096/a.webm", null, "Home",
                new MediaSource.Policy(true, true));
        assertEquals("192.168.1.4", s.networkHost().orElseThrow());
    }

    @Test
    void sharedAssetsCarryDigestNeverPath() throws Exception {
        String digest = "a".repeat(64);
        MediaSource s = MediaSource.sharedAsset("otetv:movie_night", digest, 120_000, "Movie night");
        assertEquals("shared:" + digest, s.contentIdentity());
        assertThrows(SourceException.class, () -> MediaSource.sharedAsset("otetv:x", "not-a-digest", -1, ""));
        assertThrows(SourceException.class, () -> MediaSource.sharedAsset("../../etc/passwd", digest, -1, ""));
    }

    @Test
    void localFilesAreNotShareable() throws Exception {
        assertEquals(false, MediaSource.localFile("0123456789abcdef", "Home video").isShareable());
    }

    @Test
    void classifiesPastedLinks() throws Exception {
        assertEquals(SourceType.YOUTUBE, SourceInput.fromLink("https://youtu.be/dQw4w9WgXcQ", "", MediaSource.Policy.STRICT).type());
        assertEquals(SourceType.HLS_CLEAR, SourceInput.fromLink("https://cdn.example.com/live/index.m3u8", "", MediaSource.Policy.STRICT).type());
        assertEquals(SourceType.HTTPS_VIDEO, SourceInput.fromLink("media.example.com/clip.mp4", "", MediaSource.Policy.STRICT).type());
    }
}
