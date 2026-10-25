package com.otectus.otetv.core.protocol;

import java.util.Locale;
import java.util.Optional;

/**
 * Shared picture preset for eligible direct media (section 5.3). AUTHENTIC applies the set's period treatment
 * (monochrome, scanlines, vignette); CLEAR shows the decoded picture untouched. YouTube always renders as CLEAR.
 */
public enum VisualPreset {
    AUTHENTIC,
    CLEAR;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "otetv.preset." + id();
    }

    public static Optional<VisualPreset> byId(String id) {
        for (VisualPreset p : values()) {
            if (p.id().equals(id)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public static VisualPreset byOrdinal(int ordinal) {
        VisualPreset[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : CLEAR;
    }
}
