package com.otectus.otetv.session;

import com.otectus.otetv.block.entity.TvState;
import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.protocol.TvRole;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * Role resolution from server-side facts only: the authenticated player identity, the TV's stored ACL and the
 * player's operator level. Client-supplied ownership fields and remote NBT are never permission proof (section 6.3).
 */
public final class TvAccess {
    public static final int OPERATOR_LEVEL = 2;

    private TvAccess() {
    }

    public static TvRole role(ServerPlayer player, TvState state) {
        if (player.hasPermissions(OPERATOR_LEVEL)) {
            return TvRole.OPERATOR;
        }
        return roleOf(player.getUUID(), state);
    }

    public static TvRole roleOf(UUID player, TvState state) {
        if (state.owner() != null && state.owner().equals(player)) {
            return TvRole.OWNER;
        }
        TvState.Trust trust = state.trusted().get(player);
        return trust == null ? TvRole.PUBLIC : trust.role();
    }

    public static boolean publicControlsEnabled(TvState state) {
        return OteTvServerConfig.ALLOW_PUBLIC_CONTROLS.get() && state.publicControls();
    }

    public static Set<TvPermission> permissions(ServerPlayer player, TvState state) {
        if (state.isLocked()) {
            // A TV saved by a newer version is read-only for everyone but operators.
            return player.hasPermissions(OPERATOR_LEVEL) ? Set.of(TvPermission.VIEW, TvPermission.ADMIN) : Set.of(TvPermission.VIEW);
        }
        return TvRole.effective(role(player, state), publicControlsEnabled(state));
    }

    public static boolean canReclaimLease(TvRole role) {
        return role == TvRole.OWNER || role == TvRole.OPERATOR;
    }

    public static int permissionBits(Set<TvPermission> permissions) {
        int bits = 0;
        for (TvPermission p : permissions) {
            bits |= 1 << p.ordinal();
        }
        return bits;
    }
}
