package com.otectus.otetv.core.source;

import java.util.Locale;

/** Why a source descriptor was refused. Each value has a translation key under {@code otetv.source_issue.*}. */
public enum SourceIssue {
    EMPTY,
    UNKNOWN_TYPE,
    NOT_SHAREABLE,
    BAD_URL,
    CREDENTIALS_IN_URL,
    NOT_HTTPS,
    NON_PUBLIC_HOST,
    BAD_VIDEO_ID,
    LOOKALIKE_HOST,
    BAD_START_TIME,
    BAD_ASSET_ID,
    BAD_DIGEST,
    BAD_DURATION,
    BAD_LABEL,
    BAD_CAPTIONS_URL,
    UNKNOWN_BUNDLED_ASSET,
    NOT_A_YOUTUBE_URL,
    UNSUPPORTED_YOUTUBE_FORM,
    TOO_LONG;

    public String translationKey() {
        return "otetv.source_issue." + name().toLowerCase(Locale.ROOT);
    }
}
