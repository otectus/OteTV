package com.otectus.otetv.core.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The media capability manifest (section 11.2): container + video codec + audio codec + profile combinations, each
 * with the GStreamer elements it needs. The worker reports which elements exist; this evaluates the matrix so the UI
 * can show the detected capability instead of guessing from a file extension. Combinations the release does not
 * support (HEVC, 10-bit, HDR) report UNSUPPORTED even when a decoder happens to be installed.
 */
public final class CodecMatrix {
    public enum Status { SUPPORTED, MISSING_COMPONENTS, UNSUPPORTED }

    /** One combination. Each inner list of {@code requirements} names alternatives; one of each must exist. */
    public record Combination(String id, String container, String video, String audio, String profile,
                              List<List<String>> requirements, boolean policySupported, String note) {
    }

    public record Result(Combination combination, Status status, List<String> missing) {
        public String translationKey() {
            return "otetv.codec.status." + status.name().toLowerCase(Locale.ROOT);
        }
    }

    private static final List<String> H264 = List.of("avdec_h264", "openh264dec", "vah264dec", "nvh264dec", "d3d11h264dec", "d3d12h264dec", "vulkanh264dec");
    private static final List<String> AAC = List.of("avdec_aac", "faad", "fdkaacdec");
    private static final List<String> VORBIS = List.of("vorbisdec", "avdec_vorbis");
    private static final List<String> OPUS = List.of("opusdec", "avdec_opus");

    public static final List<Combination> COMBINATIONS = List.of(
            new Combination("webm_vp8_vorbis", "WebM", "VP8", "Vorbis", "8-bit SDR",
                    List.of(List.of("matroskademux"), List.of("vp8dec", "avdec_vp8"), VORBIS), true, ""),
            new Combination("webm_vp9_opus", "WebM", "VP9", "Opus", "profile 0, 8-bit SDR",
                    List.of(List.of("matroskademux"), List.of("vp9dec", "avdec_vp9"), OPUS), true, ""),
            new Combination("mp4_h264_aac", "MP4", "H.264", "AAC", "8-bit SDR (High and lower)",
                    List.of(List.of("qtdemux"), List.of("h264parse"), H264, AAC), true, ""),
            new Combination("ogg_theora_vorbis", "Ogg", "Theora", "Vorbis", "8-bit SDR",
                    List.of(List.of("oggdemux"), List.of("theoradec"), VORBIS), true, ""),
            new Combination("ogg_opus", "Ogg", "(none)", "Opus", "audio only",
                    List.of(List.of("oggdemux"), OPUS), true, ""),
            new Combination("hls_ts_h264_aac", "HLS (MPEG-TS)", "H.264", "AAC", "unencrypted, 8-bit SDR",
                    List.of(List.of("tsdemux"), List.of("h264parse"), H264, AAC), true, ""),
            new Combination("hls_fmp4_h264_aac", "HLS (fMP4)", "H.264", "AAC", "unencrypted, 8-bit SDR",
                    List.of(List.of("qtdemux"), List.of("h264parse"), H264, AAC), true, ""),
            new Combination("mp4_hevc", "MP4", "HEVC", "any", "any",
                    List.of(), false, "HEVC is not qualified for this release"),
            new Combination("any_10bit_hdr", "any", "10-bit / HDR", "any", "HDR10, HLG, Dolby Vision",
                    List.of(), false, "Output is SDR only; HDR is never tone-mapped silently"),
            new Combination("webm_av1_opus", "WebM", "AV1", "Opus", "any",
                    List.of(), false, "AV1 is not qualified for this release"));

    private CodecMatrix() {
    }

    public static List<Result> evaluate(Set<String> availableElements) {
        List<Result> results = new ArrayList<>(COMBINATIONS.size());
        for (Combination c : COMBINATIONS) {
            if (!c.policySupported) {
                results.add(new Result(c, Status.UNSUPPORTED, List.of()));
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (List<String> alternatives : c.requirements) {
                boolean found = false;
                for (String element : alternatives) {
                    if (availableElements.contains(element)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    missing.add(String.join("|", alternatives));
                }
            }
            results.add(new Result(c, missing.isEmpty() ? Status.SUPPORTED : Status.MISSING_COMPONENTS, List.copyOf(missing)));
        }
        return results;
    }

    /** Every element name the matrix cares about; the worker reports only these to keep the reply bounded. */
    public static Set<String> relevantElements() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (Combination c : COMBINATIONS) {
            for (List<String> alt : c.requirements) {
                set.addAll(alt);
            }
        }
        set.addAll(List.of("appsrc", "appsink", "decodebin", "videoconvert", "videoscale", "audioconvert", "audioresample",
                "scaletempo", "subparse", "typefind", "queue"));
        return set;
    }
}
