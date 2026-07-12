package com.poker.hardening;

import com.poker.auth.CurrentUserPrincipal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Cheap pre-conversion budget for authenticated application frames. */
@Component
public final class InboundFrameBudgetInterceptor implements ChannelInterceptor {
    private final BoundedTokenBucketRegistry<String> sessions;
    private final BoundedTokenBucketRegistry<UUID> users;
    private final Map<String, ViolationWindow> violations = new ConcurrentHashMap<>();
    private final AbuseTelemetry telemetry;
    private final int violationThreshold;
    private final int violationCapacity;
    private final long violationWindowNanos;
    private final LongSupplier ticker;

    @Autowired
    public InboundFrameBudgetInterceptor(HardeningProperties properties, AbuseTelemetry telemetry) {
        this(properties, telemetry, System::nanoTime);
    }

    InboundFrameBudgetInterceptor(
            HardeningProperties properties,
            AbuseTelemetry telemetry,
            LongSupplier ticker) {
        this.telemetry = telemetry;
        this.ticker = ticker;
        this.violationThreshold = properties.getCoarseViolationThreshold();
        this.violationCapacity = properties.getRegistryCap();
        this.violationWindowNanos = properties.getCoarseViolationWindow().toNanos();
        this.sessions = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getFrameRate(), properties.getFrameBurst(), ticker);
        this.users = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getFrameRate(), properties.getFrameBurst(), ticker);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
        String sessionId = headers.getSessionId();
        if (headers.getMessageType() == SimpMessageType.DISCONNECT) {
            if (sessionId != null) violations.remove(sessionId);
            return message;
        }
        if (headers.getMessageType() != SimpMessageType.MESSAGE
                || headers.getDestination() == null
                || !headers.getDestination().startsWith("/app/")) {
            return message;
        }
        if (sessionId == null) {
            telemetry.frame("coarse_limited");
            return null;
        }

        boolean accepted = sessions.admit(sessionId).accepted();
        UUID userId = currentUserId(headers);
        if (accepted && userId != null) {
            accepted = users.admit(userId).accepted();
        }
        if (accepted) {
            telemetry.frame("accepted");
            return message;
        }

        telemetry.frame("coarse_limited");
        int count = violationCount(sessionId);
        if (count >= violationThreshold) {
            violations.remove(sessionId);
            telemetry.frame("closed");
            throw new MessagingException("inbound frame rate exceeded");
        }
        return null;
    }

    private int violationCount(String sessionId) {
        ViolationWindow window = violations.get(sessionId);
        if (window == null) {
            synchronized (violations) {
                window = violations.get(sessionId);
                if (window == null) {
                    if (violations.size() >= violationCapacity) return violationThreshold;
                    window = new ViolationWindow();
                    violations.put(sessionId, window);
                }
            }
        }
        return window.record(ticker.getAsLong(), violationWindowNanos);
    }

    private static UUID currentUserId(StompHeaderAccessor headers) {
        if (headers.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    @Scheduled(fixedDelayString = "${poker.abuse.cleanup-interval-ms:60000}")
    public void sweepExpiredEntries() {
        sessions.sweep(512);
        users.sweep(512);
        long now = ticker.getAsLong();
        violations.entrySet().removeIf(entry -> entry.getValue().expired(now, violationWindowNanos));
    }

    private static final class ViolationWindow {
        private long startedAt;
        private int count;

        private synchronized int record(long now, long windowNanos) {
            if (count == 0 || now < startedAt || now - startedAt >= windowNanos) {
                startedAt = now;
                count = 1;
            } else {
                count++;
            }
            return count;
        }

        private synchronized boolean expired(long now, long windowNanos) {
            if (count == 0 || now < startedAt) return false;
            try {
                return Math.subtractExact(now, startedAt) >= windowNanos;
            } catch (ArithmeticException ignored) {
                return true;
            }
        }
    }
}
