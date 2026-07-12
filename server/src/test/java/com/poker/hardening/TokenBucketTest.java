package com.poker.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void burstRefillAndRetryAreBounded() {
        AtomicLong now = new AtomicLong();
        TokenBucket bucket = new TokenBucket(10, 2, 0);
        assertThat(bucket.tryConsume(now.get()).accepted()).isTrue();
        assertThat(bucket.tryConsume(now.get()).accepted()).isTrue();
        TokenBucket.Admission limited = bucket.tryConsume(now.get());
        assertThat(limited.accepted()).isFalse();
        assertThat(limited.retryAfterMs()).isBetween(1L, 1_000L);
        now.addAndGet(100_000_000);
        assertThat(bucket.tryConsume(now.get()).accepted()).isTrue();
    }

    @Test
    void backwardClockDoesNotMintTokens() {
        TokenBucket bucket = new TokenBucket(1, 1, 100);
        assertThat(bucket.tryConsume(100).accepted()).isTrue();
        assertThat(bucket.tryConsume(50).accepted()).isFalse();
        assertThat(bucket.tryConsume(100).accepted()).isFalse();
    }

    @Test
    void registryIsHardCappedAndReclaimsExpiredEntries() {
        AtomicLong now = new AtomicLong();
        BoundedTokenBucketRegistry<String> registry = new BoundedTokenBucketRegistry<>(
                2, Duration.ofSeconds(1), 1, 1, now::get);
        assertThat(registry.admit("a").accepted()).isTrue();
        assertThat(registry.admit("b").accepted()).isTrue();
        assertThat(registry.admit("c").accepted()).isFalse();
        assertThat(registry.size()).isEqualTo(2);
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        registry.sweep(10);
        assertThat(registry.size()).isZero();
        assertThat(registry.admit("c").accepted()).isTrue();
    }

    @Test
    void concurrentFirstUseNeverExceedsHardCap() throws Exception {
        int cap = 8;
        BoundedTokenBucketRegistry<Integer> registry = new BoundedTokenBucketRegistry<>(
                cap, Duration.ofMinutes(1), 1, 1, () -> 0L);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(24)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int key = 0; key < 64; key++) {
                int identity = key;
                results.add(executor.submit(() -> {
                    start.await();
                    return registry.admit(identity).accepted();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(cap);
            assertThat(registry.size()).isEqualTo(cap);
        }
    }
}
