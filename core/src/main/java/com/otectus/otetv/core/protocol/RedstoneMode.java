package com.otectus.otetv.core.protocol;

import java.util.Locale;
import java.util.Optional;

/** Redstone behaviour (section 18.4). Redstone changes shared desired state only; it never opts anyone into media. */
public enum RedstoneMode {
    IGNORE,
    POWER_WHEN_HIGH,
    TOGGLE_POWER_ON_RISING_EDGE,
    NEXT_CHANNEL_ON_RISING_EDGE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "otetv.redstone." + id();
    }

    public RedstoneMode next() {
        RedstoneMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static Optional<RedstoneMode> byId(String id) {
        for (RedstoneMode m : values()) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public static RedstoneMode byOrdinal(int ordinal) {
        RedstoneMode[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : IGNORE;
    }
}
