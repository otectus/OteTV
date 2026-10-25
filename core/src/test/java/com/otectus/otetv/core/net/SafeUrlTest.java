package com.otectus.otetv.core.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeUrlTest {
    @Test
    void normalizesHostCaseDefaultPortFragmentAndDotSegments() throws Exception {
        SafeUrl url = SafeUrl.parse("HTTPS://Media.Example.COM:443/a/./b/../clip.webm?x=1#frag");
        assertEquals("https://media.example.com/a/clip.webm?x=1", url.toString());
        assertEquals("media.example.com", url.host());
        assertEquals(443, url.port());
        assertEquals("webm", url.extension());
    }

    @Test
    void keepsExplicitNonDefaultPort() throws Exception {
        assertEquals("https://example.com:8443/", SafeUrl.parse("https://example.com:8443").toString());
    }

    @Test
    void convertsInternationalizedHostsToPunycode() throws Exception {
        assertEquals("xn--bcher-kva.example", SafeUrl.parse("https://bücher.example/").host());
    }

    @Test
    void percentEncodesNonAsciiPath() throws Exception {
        assertEquals("/v%C3%ADdeo.webm", SafeUrl.parse("https://example.com/vídeo.webm").path());
    }

    @Test
    void rejectsCredentials() {
        UrlRejectedException e = assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://user:pw@example.com/x"));
        assertEquals(UrlRejectedException.Reason.CREDENTIALS, e.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.1/", "https://0177.0.0.1/", "https://0x7f.0.0.1/", "https://2130706433/",
            "https://017700000001/", "https://1.2.3.4.5/"})
    void rejectsAmbiguousNumericHosts(String input) {
        UrlRejectedException e = assertThrows(UrlRejectedException.class, () -> SafeUrl.parse(input));
        assertEquals(UrlRejectedException.Reason.AMBIGUOUS_NUMERIC_HOST, e.reason());
    }

    @Test
    void acceptsCanonicalIpv4AndHexLookingNames() throws Exception {
        assertTrue(SafeUrl.parse("https://93.184.216.34/").isIpLiteral());
        assertFalse(SafeUrl.parse("https://cafe.be/").isIpLiteral());
        assertFalse(SafeUrl.parse("https://1e100.net/").isIpLiteral());
    }

    @Test
    void parsesIpv6LiteralsAndRejectsZoneIds() throws Exception {
        SafeUrl url = SafeUrl.parse("https://[2606:2800:220:1:248:1893:25c8:1946]:8443/v");
        assertTrue(url.isIpLiteral());
        assertEquals(8443, url.port());
        assertEquals(UrlRejectedException.Reason.ZONE_ID,
                assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://[fe80::1%25eth0]/")).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/x", "smb://host/share", "rtsp://cam/stream",
            "javascript:alert(1)", "magnet:?xt=urn:btih:abc", "data:text/html,hi"})
    void rejectsUnsupportedSchemes(String input) {
        assertThrows(UrlRejectedException.class, () -> SafeUrl.parse(input));
    }

    @Test
    void rejectsSingleLabelHostsWhitespaceAndBackslashes() {
        assertEquals(UrlRejectedException.Reason.SINGLE_LABEL_HOST,
                assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://localhost/x")).reason());
        assertEquals(UrlRejectedException.Reason.ILLEGAL_CHARACTER,
                assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com/a b")).reason());
        assertEquals(UrlRejectedException.Reason.ILLEGAL_CHARACTER,
                assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com\\@evil.com/")).reason());
    }

    @Test
    void rejectsBadPortsPercentEncodingAndOverlongInput() {
        assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com:0/"));
        assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com:70000/"));
        assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com/%zz"));
        assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://ex%61mple.com/"));
        assertEquals(UrlRejectedException.Reason.TOO_LONG,
                assertThrows(UrlRejectedException.class, () -> SafeUrl.parse("https://example.com/" + "a".repeat(3000))).reason());
    }

    @Test
    void resolvesRelativeReferencesAndRevalidates() throws Exception {
        SafeUrl base = SafeUrl.parse("https://cdn.example.com/hls/v1/index.m3u8?token=abc");
        assertEquals("https://cdn.example.com/hls/v1/seg001.ts", base.resolve("seg001.ts").toString());
        assertEquals("https://cdn.example.com/other/init.mp4", base.resolve("/other/init.mp4").toString());
        assertEquals("https://other.example.org/a.ts", base.resolve("https://other.example.org/a.ts").toString());
        assertThrows(UrlRejectedException.class, () -> base.resolve("file:///etc/passwd"));
        assertThrows(UrlRejectedException.class, () -> base.resolve("https://user@evil.example/"));
    }

    @Test
    void hostHeaderBracketsIpv6() throws Exception {
        assertEquals("[2001:db8::1]:8080", SafeUrl.parse("http://[2001:db8::1]:8080/").hostHeader());
    }
}
