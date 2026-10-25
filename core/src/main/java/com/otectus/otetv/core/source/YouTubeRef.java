package com.otectus.otetv.core.source;

import java.util.regex.Pattern;

/** A normalized YouTube video reference: an 11-character id and a bounded start offset. */
public record YouTubeRef(String videoId, int startSeconds) {
    public static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public YouTubeRef {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("bad video id");
        }
        if (startSeconds < 0 || startSeconds > com.otectus.otetv.core.Limits.MAX_START_SECONDS) {
            throw new IllegalArgumentException("bad start");
        }
    }

    /** The canonical watch URL, constructed locally from the id (never echoed from input). */
    public String watchUrl() {
        return "https://www.youtube.com/watch?v=" + videoId + (startSeconds > 0 ? "&t=" + startSeconds + "s" : "");
    }
}
