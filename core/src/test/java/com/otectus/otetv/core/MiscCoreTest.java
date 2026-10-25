package com.otectus.otetv.core;

import com.google.gson.JsonParser;
import com.otectus.otetv.core.capability.ActionAvailability;
import com.otectus.otetv.core.capability.CodecMatrix;
import com.otectus.otetv.core.capability.PlaybackCapabilities;
import com.otectus.otetv.core.capability.PlayerAction;
import com.otectus.otetv.core.channel.ChannelDefinition;
import com.otectus.otetv.core.json.ChannelJson;
import com.otectus.otetv.core.json.RuntimeManifestJson;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.ratelimit.TokenBucket;
import com.otectus.otetv.core.redact.Redactor;
import com.otectus.otetv.core.runtime.SafeZipExtractor;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.state.MediaStateMachine;
import com.otectus.otetv.core.text.TextSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiscCoreTest {
    @Test
    void tokenBucketAllowsBurstThenRefills() {
        TokenBucket b = new TokenBucket(10, 20);
        long t = 0;
        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (b.tryAcquire(t)) {
                allowed++;
            }
        }
        assertEquals(20, allowed);
        assertFalse(b.tryAcquire(t));
        t += 100_000_000L; // 100 ms -> one token
        assertTrue(b.tryAcquire(t));
        assertFalse(b.tryAcquire(t));
    }

    @Test
    void sanitizerRemovesControlBidiAndFormattingCodes() {
        assertEquals("Movie Night", TextSanitizer.sanitize("  Movie\u0000 \u202E\u00a7cNight\n", 64).replace("c", ""));
        assertEquals("abc", TextSanitizer.sanitize("abcdef", 3));
        assertEquals("a b", TextSanitizer.sanitize("a\t\t b", 10));
        String emoji = "\uD83D\uDC68\u200D\uD83D\uDC69";
        assertEquals(emoji, TextSanitizer.sanitize(emoji, 10));
        assertEquals(1, TextSanitizer.sanitize("\uD83D\uDE00\uD83D\uDE00", 1).codePointCount(0, 2));
    }

    @Test
    void redactorHidesQueriesTokensAndPaths() {
        assertEquals("https://cdn.example.com/\u2026.m3u8?<redacted>", Redactor.redactUrl("https://user:pw@cdn.example.com/a/b/live.m3u8?token=SECRET"));
        String line = Redactor.redact("failed https://x.example/v.webm?sig=abc at /home/alice/Videos/private.webm token=deadbeef");
        assertFalse(line.contains("alice"));
        assertFalse(line.contains("sig=abc"));
        assertFalse(line.contains("deadbeef"));
        assertFalse(line.contains("private"));
        assertFalse(Redactor.redact("C:\\Users\\Bob\\Videos\\x.mp4").contains("Bob"));
    }

    @Test
    void rolesAndPublicControls() {
        assertFalse(TvRole.effective(TvRole.PUBLIC, false).contains(TvPermission.PLAYBACK));
        assertTrue(TvRole.effective(TvRole.PUBLIC, true).contains(TvPermission.PLAYBACK));
        assertFalse(TvRole.effective(TvRole.PUBLIC, true).contains(TvPermission.SOURCE));
        assertTrue(TvRole.EDITOR.permissions().contains(TvPermission.SOURCE));
        assertFalse(TvRole.EDITOR.permissions().contains(TvPermission.ACL));
        assertTrue(TvRole.OPERATOR.permissions().contains(TvPermission.ADMIN));
        assertFalse(TvRole.OWNER.isAssignable());
    }

    @Test
    void actionAvailabilityIntersectsPermissionCapabilityAndState() {
        ActionAvailability.Context viewer = new ActionAvailability.Context(TvRole.effective(TvRole.PUBLIC, false), false, false,
                true, SourceType.HTTPS_VIDEO, null, false, false, false, true, false, true);
        assertFalse(ActionAvailability.evaluate(PlayerAction.PLAY_PAUSE, viewer).enabled());
        assertEquals("otetv.disabled.no_permission", ActionAvailability.evaluate(PlayerAction.PLAY_PAUSE, viewer).reasonKey());
        assertTrue(ActionAvailability.evaluate(PlayerAction.JOIN, viewer).enabled());

        PlaybackCapabilities live = PlaybackCapabilities.sourceDefaults(SourceType.HLS_CLEAR).with(PlaybackCapabilities.Flag.LIVE, true);
        ActionAvailability.Context owner = new ActionAvailability.Context(TvRole.OWNER.permissions(), false, false, true,
                SourceType.HLS_CLEAR, live, true, false, false, true, false, true);
        assertEquals("otetv.disabled.live_no_seek", ActionAvailability.evaluate(PlayerAction.SEEK_FORWARD, owner).reasonKey());
        assertEquals("otetv.disabled.live_no_pause", ActionAvailability.evaluate(PlayerAction.PLAY_PAUSE, owner).reasonKey());

        ActionAvailability.Context leased = new ActionAvailability.Context(TvRole.CONTROLLER.permissions(), true, false, true,
                SourceType.BUNDLED, null, true, false, true, true, false, true);
        assertEquals("otetv.disabled.lease_held", ActionAvailability.evaluate(PlayerAction.STOP, leased).reasonKey());
        assertTrue(ActionAvailability.evaluate(PlayerAction.LOCAL_MUTE, leased).enabled(), "local actions ignore leases");
    }

    @Test
    void youTubeNeverOffersEffectsOrRateControl() {
        PlaybackCapabilities yt = PlaybackCapabilities.sourceDefaults(SourceType.YOUTUBE);
        assertFalse(yt.has(PlaybackCapabilities.Flag.EFFECTS_ELIGIBLE));
        assertFalse(yt.has(PlaybackCapabilities.Flag.RATE_CONTROL));
        assertTrue(yt.has(PlaybackCapabilities.Flag.BEST_EFFORT_SYNC));
        assertEquals(yt, PlaybackCapabilities.fromBits(yt.toBits(), -1));
    }

    @Test
    void codecMatrixReportsMissingComponentsAndUnsupportedCombos() {
        List<CodecMatrix.Result> results = CodecMatrix.evaluate(Set.of("oggdemux", "theoradec", "vorbisdec", "avdec_vp8"));
        CodecMatrix.Result webm = results.stream().filter(r -> r.combination().id().equals("webm_vp8_vorbis")).findFirst().orElseThrow();
        assertEquals(CodecMatrix.Status.MISSING_COMPONENTS, webm.status());
        assertEquals(List.of("matroskademux"), webm.missing());
        CodecMatrix.Result ogg = results.stream().filter(r -> r.combination().id().equals("ogg_theora_vorbis")).findFirst().orElseThrow();
        assertEquals(CodecMatrix.Status.SUPPORTED, ogg.status());
        CodecMatrix.Result hevc = results.stream().filter(r -> r.combination().id().equals("mp4_hevc")).findFirst().orElseThrow();
        assertEquals(CodecMatrix.Status.UNSUPPORTED, hevc.status());
        assertTrue(CodecMatrix.supports("ogg_theora_vorbis", Set.of("oggdemux", "theoradec", "vorbisdec")));
        assertFalse(CodecMatrix.supports("webm_vp8_vorbis", Set.of("oggdemux", "theoradec", "vorbisdec", "avdec_vp8")));
        assertFalse(CodecMatrix.supports("mp4_hevc", Set.of("qtdemux", "avdec_h265")));
        assertFalse(CodecMatrix.supports("no_such_combination", Set.of()));
    }

    @Test
    void stateMachineIgnoresStaleGenerations() {
        MediaStateMachine m = new MediaStateMachine();
        m.beginGeneration(5, 0);
        assertFalse(m.transition(4, LocalPlaybackState.PLAYING, 1), "old generation must not apply");
        assertTrue(m.transition(5, LocalPlaybackState.PLAYING, 2));
        assertFalse(m.fail(4, ErrorCode.NETWORK, "late", 3));
        assertEquals(LocalPlaybackState.PLAYING, m.state());
        assertTrue(m.fail(5, ErrorCode.UNSUPPORTED_CODEC, "hevc", 4));
        assertEquals(LocalPlaybackState.UNSUPPORTED, m.state());
    }

    @Test
    void channelJsonValidatesEntriesIndividually() {
        String ok = """
                {"schemaVersion":1,"id":"otetv:welcome","number":1,"displayName":{"translate":"channel.otetv.welcome"},
                 "source":{"type":"bundled","assetId":"otetv:welcome"}}""";
        ChannelJson.Result r = ChannelJson.parse(JsonParser.parseString(ok), MediaSource.Policy.STRICT, ChannelDefinition.Origin.DATAPACK);
        ChannelDefinition def = assertInstanceOf(ChannelJson.Ok.class, r).definition();
        assertEquals(1, def.number());
        assertTrue(def.translatable());

        String yt = """
                {"schemaVersion":1,"id":"example:yt","number":7,"displayName":"Clip",
                 "source":{"type":"youtube","videoId":"dQw4w9WgXcQ","startSeconds":30}}""";
        ChannelDefinition ytDef = assertInstanceOf(ChannelJson.Ok.class,
                ChannelJson.parse(JsonParser.parseString(yt), MediaSource.Policy.STRICT, ChannelDefinition.Origin.DATAPACK)).definition();
        assertEquals(30, ytDef.source().youtube().orElseThrow().startSeconds());

        for (String bad : List.of(
                "{\"schemaVersion\":2,\"id\":\"a:b\",\"number\":1,\"displayName\":\"x\",\"source\":{\"type\":\"bundled\",\"assetId\":\"a:b\"}}",
                "{\"schemaVersion\":1,\"id\":\"a:b\",\"number\":0,\"displayName\":\"x\",\"source\":{\"type\":\"bundled\",\"assetId\":\"a:b\"}}",
                "{\"schemaVersion\":1,\"id\":\"a:b\",\"number\":1,\"displayName\":\"x\",\"extra\":1,\"source\":{\"type\":\"bundled\",\"assetId\":\"a:b\"}}",
                "{\"schemaVersion\":1,\"id\":\"a:b\",\"number\":1,\"displayName\":\"x\",\"source\":{\"type\":\"java_class\",\"class\":\"x.Y\"}}",
                "{\"schemaVersion\":1,\"id\":\"a:b\",\"number\":1,\"displayName\":\"x\",\"source\":{\"type\":\"local_file\",\"localKey\":\"0123456789abcdef\"}}",
                "{\"schemaVersion\":1,\"id\":\"a:b\",\"number\":1,\"displayName\":\"x\",\"source\":{\"type\":\"https_video\",\"url\":\"https://u:p@x.example/a.webm\"}}")) {
            assertInstanceOf(ChannelJson.Failed.class, ChannelJson.parse(JsonParser.parseString(bad), MediaSource.Policy.STRICT, ChannelDefinition.Origin.DATAPACK), bad);
        }
    }

    @Test
    void channelDuplicatesAreReported() throws Exception {
        MediaSource s = MediaSource.bundled("otetv:welcome", "");
        List<String> problems = ChannelJson.findDuplicates(List.of(
                new ChannelDefinition("a:one", 1, "One", false, "", s, true, ChannelDefinition.Origin.DATAPACK),
                new ChannelDefinition("a:two", 1, "Two", false, "", s, true, ChannelDefinition.Origin.DATAPACK)));
        assertEquals(1, problems.size());
    }

    @Test
    void zipExtractorRejectsTraversalAndHonoursSizeLimit(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("good.zip");
        writeZip(good, "bin/worker", new byte[100], "licenses/LICENSE.txt", new byte[10]);
        long written = SafeZipExtractor.extract(good, dir.resolve("out"), 1000, List.of("bin/worker"));
        assertEquals(110, written);
        assertTrue(Files.isRegularFile(dir.resolve("out/bin/worker")));

        Path evil = dir.resolve("evil.zip");
        writeZip(evil, "../escape.txt", new byte[1]);
        assertThrows(IOException.class, () -> SafeZipExtractor.extract(evil, dir.resolve("out2"), 1000, List.of()));
        assertFalse(Files.exists(dir.resolve("escape.txt")));

        Path big = dir.resolve("big.zip");
        writeZip(big, "a.bin", new byte[5000]);
        assertThrows(IOException.class, () -> SafeZipExtractor.extract(big, dir.resolve("out3"), 1000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> SafeZipExtractor.validateEntryName("C:/Windows/x.dll"));
    }

    private static void writeZip(Path path, Object... nameAndData) throws IOException {
        try (OutputStream os = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (int i = 0; i < nameAndData.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) nameAndData[i]));
                zip.write((byte[]) nameAndData[i + 1]);
                zip.closeEntry();
            }
        }
    }

    @Test
    void runtimeManifestRejectsNonHttpsAndTraversal() {
        String good = """
                {"schemaVersion":1,"bundles":[{"id":"otetv-browser","version":"156.0.14-1","os":"linux","arch":"x86_64",
                "urls":["https://downloads.example.org/otetv-browser.zip"],"sha256":"%s","size":10,"unpackedSize":20,
                "protocol":{"min":1,"max":1},"entrypoint":"otetv-browser-worker","executables":["otetv-browser-worker"]}]}"""
                .formatted("a".repeat(64));
        assertEquals(1, RuntimeManifestJson.parse(good).size());
        assertThrows(IllegalArgumentException.class, () -> RuntimeManifestJson.parse(good.replace("https://", "http://")));
        assertThrows(IllegalArgumentException.class, () -> RuntimeManifestJson.parse(good.replace("\"entrypoint\":\"otetv-browser-worker\"", "\"entrypoint\":\"../../bin/sh\"")));
    }
}
