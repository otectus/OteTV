package com.otectus.otetv.session;

import java.util.UUID;

/**
 * A renewable short control lease (section 6.2): prevents two remotes from fighting. The owner or an operator can
 * reclaim it; disconnect, dimension change and inactivity expire it. Each new holder bumps the generation so a stale
 * AcquireControl/ReleaseControl cannot act on someone else's lease.
 */
public record ControlLease(UUID holder, String holderName, long expiresAtNanos, int generation) {
    public boolean isExpired(long nowNanos) {
        return nowNanos >= expiresAtNanos;
    }

    public boolean heldBy(UUID player) {
        return holder.equals(player);
    }

    public ControlLease renewed(long nowNanos, long durationNanos) {
        return new ControlLease(holder, holderName, nowNanos + durationNanos, generation);
    }

    public long remainingMs(long nowNanos) {
        return Math.max(0, (expiresAtNanos - nowNanos) / 1_000_000L);
    }
}
