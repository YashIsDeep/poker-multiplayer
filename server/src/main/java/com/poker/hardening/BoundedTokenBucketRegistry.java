package com.poker.hardening;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fair, hard-capped token-bucket registry. Existing identities consume under
 * only their entry lock; the registry lock is used for creation and eviction.
 */
public final class BoundedTokenBucketRegistry<K> {
    private static final int OPPORTUNISTIC_SWEEP_SIZE = 32;
    private static final long CAP_RETRY_AFTER_MS = 1_000;

    private final Map<K, Entry> entries = new ConcurrentHashMap<>();
    private final ArrayDeque<K> rotation = new ArrayDeque<>();
    private final Object registryLock = new Object();
    private final int capacity;
    private final long ttlNanos;
    private final double refillPerSecond;
    private final int burstCapacity;
    private final LongSupplier ticker;

    public BoundedTokenBucketRegistry(
            int capacity,
            Duration ttl,
            double refillPerSecond,
            int burstCapacity,
            LongSupplier ticker) {
        if (capacity < 1 || ttl == null || ttl.isZero() || ttl.isNegative()
                || !Double.isFinite(refillPerSecond) || refillPerSecond <= 0
                || burstCapacity < 1) {
            throw new IllegalArgumentException("invalid token bucket registry configuration");
        }
        this.capacity = capacity;
        this.ttlNanos = ttl.toNanos();
        this.refillPerSecond = refillPerSecond;
        this.burstCapacity = burstCapacity;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    public TokenBucket.Admission admit(K key) {
        Objects.requireNonNull(key, "key");
        long now = ticker.getAsLong();
        while (true) {
            Entry entry = entries.get(key);
            if (entry == null) {
                entry = createEntry(key, now);
                if (entry == null) {
                    return new TokenBucket.Admission(false, CAP_RETRY_AFTER_MS);
                }
            }

            synchronized (entry) {
                // A sweeper may have removed this entry before we acquired its
                // lock. Never admit against an orphaned bucket.
                if (entries.get(key) != entry) {
                    continue;
                }
                entry.lastAccessNanos = now;
                return entry.bucket.tryConsume(now);
            }
        }
    }

    public int size() {
        return entries.size();
    }

    public void sweep(int maximumEntries) {
        if (maximumEntries < 0) {
            throw new IllegalArgumentException("maximumEntries cannot be negative");
        }
        synchronized (registryLock) {
            sweepLocked(ticker.getAsLong(), maximumEntries);
        }
    }

    private Entry createEntry(K key, long now) {
        synchronized (registryLock) {
            Entry existing = entries.get(key);
            if (existing != null) {
                return existing;
            }
            sweepLocked(now, OPPORTUNISTIC_SWEEP_SIZE);
            if (entries.size() >= capacity) {
                return null;
            }
            Entry created = new Entry(new TokenBucket(refillPerSecond, burstCapacity, now), now);
            entries.put(key, created);
            rotation.addLast(key);
            return created;
        }
    }

    private void sweepLocked(long now, int maximumEntries) {
        for (int inspected = 0; inspected < maximumEntries && !rotation.isEmpty(); inspected++) {
            K key = rotation.removeFirst();
            Entry entry = entries.get(key);
            if (entry == null) {
                continue;
            }
            synchronized (entry) {
                if (entries.get(key) != entry) {
                    continue;
                }
                if (isExpired(now, entry.lastAccessNanos)) {
                    entries.remove(key, entry);
                } else {
                    rotation.addLast(key);
                }
            }
        }
    }

    private boolean isExpired(long now, long lastAccess) {
        if (now < lastAccess) return false;
        long elapsed;
        try {
            elapsed = Math.subtractExact(now, lastAccess);
        } catch (ArithmeticException ignored) {
            elapsed = Long.MAX_VALUE;
        }
        return elapsed >= ttlNanos;
    }

    private final class Entry {
        private final TokenBucket bucket;
        private long lastAccessNanos;

        private Entry(TokenBucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
