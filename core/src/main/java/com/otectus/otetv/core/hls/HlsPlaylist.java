package com.otectus.otetv.core.hls;

import com.otectus.otetv.core.net.SafeUrl;

import java.util.List;

/** Parsed playlist types (RFC 8216 subset). All URIs are already resolved and validated {@link SafeUrl}s. */
public sealed interface HlsPlaylist permits HlsPlaylist.Master, HlsPlaylist.Media {

    record Variant(SafeUrl uri, long bandwidth, int width, int height, String codecs, String audioGroup, double frameRate) {
        public boolean hasResolution() {
            return width > 0 && height > 0;
        }
    }

    record Rendition(String type, String groupId, String name, String language, SafeUrl uri, boolean isDefault) {
    }

    record Master(List<Variant> variants, List<Rendition> renditions) implements HlsPlaylist {
    }

    record ByteRange(long length, long offset) {
    }

    record Segment(SafeUrl uri, double durationSeconds, long sequence, boolean discontinuity, ByteRange byteRange,
                   SafeUrl initMap, ByteRange initMapRange, String programDateTime) {
    }

    record Media(double targetDuration, long mediaSequence, boolean endList, String playlistType, List<Segment> segments,
                 boolean independentSegments) implements HlsPlaylist {
        public boolean isLive() {
            return !endList && !"VOD".equals(playlistType);
        }

        public double totalDurationSeconds() {
            double total = 0;
            for (Segment s : segments) {
                total += s.durationSeconds();
            }
            return total;
        }

        /** Index of the segment containing {@code seconds}, clamped to the playlist. */
        public int segmentIndexAt(double seconds) {
            double t = 0;
            for (int i = 0; i < segments.size(); i++) {
                double d = segments.get(i).durationSeconds();
                if (seconds < t + d) {
                    return i;
                }
                t += d;
            }
            return Math.max(0, segments.size() - 1);
        }

        public double segmentStartSeconds(int index) {
            double t = 0;
            for (int i = 0; i < index && i < segments.size(); i++) {
                t += segments.get(i).durationSeconds();
            }
            return t;
        }

        /**
         * RFC 8216 section 6.3.3: clients should not start a live stream closer than three target durations from
         * the end of the playlist.
         */
        public int liveStartIndex() {
            double fromEnd = 0;
            for (int i = segments.size() - 1; i >= 0; i--) {
                fromEnd += segments.get(i).durationSeconds();
                if (fromEnd >= targetDuration * 3) {
                    return i;
                }
            }
            return 0;
        }
    }
}
