package com.otectus.otetv.core.protocol;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Roles from section 6.3. Owner-only editing is the default; trusted players get configurable playback and channel
 * control (CONTROLLER) or additionally source/queue/settings editing (EDITOR). Public viewers may join and change
 * their own local preferences only, unless public playback controls are enabled on both the server and the TV.
 */
public enum TvRole {
    PUBLIC(EnumSet.of(TvPermission.VIEW, TvPermission.JOIN)),
    VIEWER(EnumSet.of(TvPermission.VIEW, TvPermission.JOIN)),
    CONTROLLER(EnumSet.of(TvPermission.VIEW, TvPermission.JOIN, TvPermission.POWER, TvPermission.PLAYBACK, TvPermission.CHANNEL)),
    EDITOR(EnumSet.of(TvPermission.VIEW, TvPermission.JOIN, TvPermission.POWER, TvPermission.PLAYBACK, TvPermission.CHANNEL,
            TvPermission.QUEUE_EDIT, TvPermission.SOURCE, TvPermission.SETTINGS)),
    OWNER(EnumSet.of(TvPermission.VIEW, TvPermission.JOIN, TvPermission.POWER, TvPermission.PLAYBACK, TvPermission.CHANNEL,
            TvPermission.QUEUE_EDIT, TvPermission.SOURCE, TvPermission.SETTINGS, TvPermission.ACL, TvPermission.RESET)),
    OPERATOR(EnumSet.allOf(TvPermission.class));

    private final Set<TvPermission> permissions;

    TvRole(EnumSet<TvPermission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public Set<TvPermission> permissions() {
        return permissions;
    }

    /** Roles an owner can hand out with {@code /otetv trust}. */
    public boolean isAssignable() {
        return this == VIEWER || this == CONTROLLER || this == EDITOR;
    }

    public boolean atLeast(TvRole other) {
        return ordinal() >= other.ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "otetv.role." + id();
    }

    public static Optional<TvRole> byId(String id) {
        for (TvRole r : values()) {
            if (r.id().equals(id)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /**
     * Effective permissions: the role's own set, plus playback/power/channel for public viewers when public
     * controls are enabled on both the server and this TV.
     */
    public static Set<TvPermission> effective(TvRole role, boolean publicControlsEnabled) {
        EnumSet<TvPermission> set = EnumSet.copyOf(role.permissions);
        if (publicControlsEnabled && (role == PUBLIC || role == VIEWER)) {
            set.add(TvPermission.POWER);
            set.add(TvPermission.PLAYBACK);
            set.add(TvPermission.CHANNEL);
        }
        return set;
    }
}
