package com.poker.hardening;

import com.poker.ws.protocol.CommandType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Low-cardinality, privacy-safe, best-effort abuse telemetry. */
@Component
public final class AbuseTelemetry {
    private static final Logger log = LoggerFactory.getLogger(AbuseTelemetry.class);
    private static final int MAX_LOG_WINDOWS = 10_000;
    private static final int LOG_EVERY_REJECTIONS = 100;
    private static final long LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final MeterRegistry meters;
    private final DistributionSummary retryAfter;
    private final byte[] processSalt = new byte[32];
    private final Map<RejectionKey, RejectionWindow> rejectionWindows =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RejectionKey, RejectionWindow> eldest) {
                    return size() > MAX_LOG_WINDOWS;
                }
            };

    public AbuseTelemetry(MeterRegistry meters) {
        this.meters = meters;
        this.retryAfter = DistributionSummary.builder("poker.stomp.limit.retry").register(meters);
        new SecureRandom().nextBytes(processSalt);
    }

    public void command(
            CommandType type,
            UUID userId,
            boolean accepted,
            long retryAfterMs) {
        try {
            meters.counter(
                    "poker.stomp.commands",
                    "command", type.name(),
                    "outcome", accepted ? "accepted" : "limited").increment();
            if (!accepted) {
                retryAfter.record(retryAfterMs);
                sampleLimitedLog(type, userId);
            }
        } catch (RuntimeException ignored) {
            // Telemetry must never change command admission behavior.
        }
    }

    public void frame(String outcome) {
        try {
            meters.counter("poker.stomp.frames", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Telemetry must never change transport admission behavior.
        }
    }

    private void sampleLimitedLog(CommandType type, UUID userId) {
        if (userId == null) return;
        long now = System.nanoTime();
        long count;
        boolean shouldLog;
        RejectionKey key = new RejectionKey(userKey(userId), type);
        synchronized (rejectionWindows) {
            RejectionWindow window = rejectionWindows.computeIfAbsent(
                    key, ignored -> new RejectionWindow());
            count = ++window.count;
            shouldLog = count == 1 || count % LOG_EVERY_REJECTIONS == 0
                    || elapsed(now, window.lastLoggedAt) >= LOG_INTERVAL_NANOS;
            if (shouldLog) window.lastLoggedAt = now;
        }
        if (shouldLog) {
            log.warn("STOMP command limited command={} userKey={} rejectionCount={}",
                    type, key.userKey(), count);
        }
    }

    private String userKey(UUID userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(processSalt);
            digest.update(ByteBuffer.allocate(16)
                    .putLong(userId.getMostSignificantBits())
                    .putLong(userId.getLeastSignificantBits())
                    .array());
            return HexFormat.of().formatHex(digest.digest(), 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static long elapsed(long now, long then) {
        if (then == 0) return Long.MAX_VALUE;
        if (now < then) return 0;
        try {
            return Math.subtractExact(now, then);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record RejectionKey(String userKey, CommandType command) {}

    private static final class RejectionWindow {
        private long count;
        private long lastLoggedAt;
    }
}
