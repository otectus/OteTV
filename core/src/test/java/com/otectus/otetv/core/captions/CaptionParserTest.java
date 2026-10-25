package com.otectus.otetv.core.captions;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptionParserTest {
    @Test
    void parsesWebVttStrippingMarkupAndSkippingNotes() throws Exception {
        String vtt = """
                WEBVTT - demo

                NOTE this is a comment

                intro
                00:00:01.000 --> 00:00:03.500 align:start
                <v Narrator><b>Hello</b> &amp; welcome</v>

                00:04.000 --> 00:06.000
                Line one
                Line two
                """;
        List<Cue> cues = CaptionParser.parse(vtt.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, cues.size());
        assertEquals(1000, cues.get(0).startMs());
        assertEquals(3500, cues.get(0).endMs());
        assertEquals("Hello & welcome", cues.get(0).text());
        assertEquals("Line one\nLine two", cues.get(1).text());
    }

    @Test
    void parsesSrtAndDropsInvalidTimings() throws Exception {
        String srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>First</i> {\\an8}\r\n\r\n"
                + "2\r\n00:00:05,000 --> 00:00:04,000\r\nBackwards\r\n\r\n"
                + "3\r\n00:00:06,000 --> 00:00:07,250\r\nThird ‮line\r\n";
        List<Cue> cues = CaptionParser.parse(srt.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, cues.size());
        assertEquals("First", cues.get(0).text());
        assertEquals("Third line", cues.get(1).text());
        assertEquals(7250, cues.get(1).endMs());
    }

    @Test
    void rejectsFilesWithoutValidCues() {
        assertThrows(CaptionParser.CaptionException.class, () -> CaptionParser.parse("WEBVTT\n\nnothing here\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(CaptionParser.CaptionException.class, () -> CaptionParser.parse("garbage".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void findsTheActiveCue() throws Exception {
        List<Cue> cues = CaptionParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nA\n\n00:03.000 --> 00:04.000\nB\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("A", CaptionParser.activeCue(cues, 1500).text());
        assertNull(CaptionParser.activeCue(cues, 2500));
        assertEquals("B", CaptionParser.activeCue(cues, 3000).text());
        assertNull(CaptionParser.activeCue(cues, 500));
    }

    @Test
    void boundsLinesPerCue() throws Exception {
        List<Cue> cues = CaptionParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\n1\n2\n3\n4\n5\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("1\n2\n3", cues.get(0).text());
    }
}
