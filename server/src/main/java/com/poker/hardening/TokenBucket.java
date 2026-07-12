package com.poker.hardening;

/** Thread-safe monotonic token bucket with saturating elapsed-time handling. */
public final class TokenBucket {
    private final double refillPerNano;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double perSecond, int capacity, long nowNanos) {
        if (!Double.isFinite(perSecond) || perSecond <= 0 || capacity < 1) {
            throw new IllegalArgumentException("invalid bucket");
        }
        this.refillPerNano = perSecond / 1_000_000_000d;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public synchronized Admission tryConsume(long now) {
        if (now > lastRefillNanos) {
            long elapsed;
            try {
                elapsed = Math.subtractExact(now, lastRefillNanos);
            } catch (ArithmeticException ignored) {
                elapsed = Long.MAX_VALUE;
            }
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = now;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return new Admission(true, 0);
        }
        long retry = (long) Math.ceil((1 - tokens) / refillPerNano / 1_000_000d);
        return new Admission(false, Math.max(1, Math.min(1_000, retry)));
    }

    public record Admission(boolean accepted, long retryAfterMs) {}
}
