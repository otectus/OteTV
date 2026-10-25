package com.otectus.otetv.core.ratelimit;

/** A token bucket on a caller-supplied monotonic clock, so tests are deterministic. Not thread-safe. */
public final class TokenBucket {
    private final double ratePerNano;
    private final double capacity;
    private double tokens;
    private long lastNanos;
    private boolean started;

    public TokenBucket(double ratePerSecond, int burst) {
        if (ratePerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("rate and burst must be positive");
        }
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.capacity = burst;
        this.tokens = burst;
    }

    public boolean tryAcquire(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public double available(long nowNanos) {
        refill(nowNanos);
        return tokens;
    }

    private void refill(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
            return;
        }
        long elapsed = nowNanos - lastNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * ratePerNano);
            lastNanos = nowNanos;
        }
    }
}
