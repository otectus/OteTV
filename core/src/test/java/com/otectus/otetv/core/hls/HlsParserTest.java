package com.otectus.otetv.core.hls;

import com.otectus.otetv.core.net.SafeUrl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsParserTest {
    private static final SafeUrl BASE;

    static {
        try {
            BASE = SafeUrl.parse("https://cdn.example.com/show/master.m3u8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HlsPlaylist parse(String text) throws HlsException {
        return HlsParser.parse(text.getBytes(StandardCharsets.UTF_8), BASE);
    }

    @Test
    void parsesMasterVariantsAndRenditions() throws Exception {
        HlsPlaylist p = parse("""
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English, main",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",FRAME-RATE=30.000
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud"
                https://other.example.net/high/index.m3u8
                """);
        HlsPlaylist.Master m = assertInstanceOf(HlsPlaylist.Master.class, p);
        assertEquals(2, m.variants().size());
        assertEquals("https://cdn.example.com/show/low/index.m3u8", m.variants().get(0).uri().toString());
        assertEquals(640, m.variants().get(0).width());
        assertEquals("aud", m.variants().get(1).audioGroup());
        assertEquals("English, main", m.renditions().get(0).name());
    }

    @Test
    void parsesVodMediaPlaylistWithDiscontinuityAndByteRanges() throws Exception {
        HlsPlaylist p = parse("""
                #EXTM3U
                #EXT-X-VERSION:4
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:6.0,
                #EXT-X-BYTERANGE:1000@0
                all.ts
                #EXTINF:6.0,
                #EXT-X-BYTERANGE:1500
                all.ts
                #EXT-X-DISCONTINUITY
                #EXT-X-PROGRAM-DATE-TIME:2026-10-25T12:00:00.000Z
                #EXTINF:4.5,
                part3.ts
                #EXT-X-ENDLIST
                """);
        HlsPlaylist.Media media = assertInstanceOf(HlsPlaylist.Media.class, p);
        assertFalse(media.isLive());
        assertEquals(3, media.segments().size());
        assertEquals(1000, media.segments().get(1).byteRange().offset());
        assertEquals(102, media.segments().get(2).sequence());
        assertTrue(media.segments().get(2).discontinuity());
        assertEquals(16.5, media.totalDurationSeconds(), 1e-9);
        assertEquals(1, media.segmentIndexAt(7.0));
        assertEquals(6.0, media.segmentStartSeconds(1), 1e-9);
    }

    @Test
    void liveStartsThreeTargetDurationsFromTheEnd() throws Exception {
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:5\n");
        for (int i = 0; i < 10; i++) {
            sb.append("#EXTINF:4.0,\nseg").append(i).append(".ts\n");
        }
        HlsPlaylist.Media media = assertInstanceOf(HlsPlaylist.Media.class, parse(sb.toString()));
        assertTrue(media.isLive());
        assertEquals(7, media.liveStartIndex());
    }

    @Test
    void rejectsEncryptedPlaylists() {
        HlsException e = assertThrows(HlsException.class, () -> parse("""
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k"
                #EXTINF:6.0,
                a.ts
                """));
        assertEquals(HlsException.Reason.ENCRYPTED, e.reason());
        HlsException s = assertThrows(HlsException.class, () -> parse("""
                #EXTM3U
                #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://x"
                #EXT-X-STREAM-INF:BANDWIDTH=1
                v.m3u8
                """));
        assertEquals(HlsException.Reason.ENCRYPTED, s.reason());
    }

    @Test
    void rejectsUnsafeReferencesNestingAndMalformedInput() {
        assertEquals(HlsException.Reason.BAD_URI, assertThrows(HlsException.class, () -> parse("""
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                file:///etc/passwd
                """)).reason());
        assertEquals(HlsException.Reason.UNSUPPORTED, assertThrows(HlsException.class, () -> parse("""
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                nested.m3u8
                """)).reason());
        assertEquals(HlsException.Reason.MALFORMED, assertThrows(HlsException.class, () -> parse("not a playlist")).reason());
        assertEquals(HlsException.Reason.MALFORMED, assertThrows(HlsException.class, () -> parse("#EXTM3U\n#EXTINF:6,\na.ts\n")).reason());
        assertEquals(HlsException.Reason.TOO_LARGE, assertThrows(HlsException.class,
                () -> HlsParser.parse(new byte[HlsParser.MAX_BYTES + 1], BASE)).reason());
    }

    @Test
    void attributeListsHonourQuotedCommas() throws Exception {
        var a = HlsParser.attributes("TYPE=SUBTITLES,NAME=\"A, B\",URI=\"x.m3u8\"");
        assertEquals("A, B", a.get("NAME"));
        assertEquals("x.m3u8", a.get("URI"));
    }
}
