package com.poker.hardening;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.ws.WebSocketTransportRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Cheap pre-conversion budgets for application and STOMP lifecycle frames. */
@Component
public final class InboundFrameBudgetInterceptor implements ChannelInterceptor {
    private final BoundedTokenBucketRegistry<String> sessions;
    private final BoundedTokenBucketRegistry<UUID> users;
    private final BoundedTokenBucketRegistry<String> lifecycleSessions;
    private final BoundedTokenBucketRegistry<String> lifecycleIdentities;
    private final BoundedTokenBucketRegistry<String> heartbeatSessions;
    private final WebSocketTransportRegistry transports;
    private final Map<String, ViolationWindow> violations = new ConcurrentHashMap<>();
    private final AbuseTelemetry telemetry;
    private final int violationThreshold;
    private final int violationCapacity;
    private final long violationWindowNanos;
    private final LongSupplier ticker;

    @Autowired
    public InboundFrameBudgetInterceptor(HardeningProperties properties, AbuseTelemetry telemetry,
            WebSocketTransportRegistry transports) {
        this(properties, telemetry, transports, System::nanoTime);
    }

    InboundFrameBudgetInterceptor(
            HardeningProperties properties,
            AbuseTelemetry telemetry,
            WebSocketTransportRegistry transports,
            LongSupplier ticker) {
        this.telemetry = telemetry;
        this.transports = transports;
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
        this.lifecycleSessions = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getLifecycleFrameRate(), properties.getLifecycleFrameBurst(), ticker);
        this.lifecycleIdentities = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getLifecycleFrameRate(), properties.getLifecycleFrameBurst(), ticker);
        this.heartbeatSessions = new BoundedTokenBucketRegistry<>(
                properties.getRegistryCap(), properties.getRegistryTtl(),
                properties.getHeartbeatFrameRate(), properties.getHeartbeatFrameBurst(), ticker);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
        String sessionId = headers.getSessionId();
        if (headers.getMessageType() == SimpMessageType.DISCONNECT) {
            cleanup(sessionId);
            return message;
        }
        StompCommand command = headers.getCommand();
        if (headers.getMessageType() == SimpMessageType.HEARTBEAT) {
            return admitHeartbeat(message, sessionId);
        }
        boolean lifecycle = command == StompCommand.CONNECT || command == StompCommand.STOMP
                || command == StompCommand.SUBSCRIBE || command == StompCommand.UNSUBSCRIBE;
        if (!lifecycle && (headers.getMessageType() != SimpMessageType.MESSAGE
                || headers.getDestination() == null
                || !headers.getDestination().startsWith("/app/"))) {
            return message;
        }
        if (sessionId == null || !transports.isOpen(sessionId)) {
            telemetry.frame("coarse_limited");
            return null;
        }

        boolean accepted = (lifecycle ? lifecycleSessions : sessions).admit(sessionId).accepted();
        UUID userId = currentUserId(headers);
        if (accepted && userId != null) {
            accepted = lifecycle ? lifecycleIdentities.admit("user:" + userId).accepted()
                    : users.admit(userId).accepted();
        }
        if (accepted && lifecycle && headers.getSessionAttributes() != null) {
            String httpSessionId = SessionRepositoryMessageInterceptor.getSessionId(headers.getSessionAttributes());
            if (httpSessionId != null) accepted = lifecycleIdentities.admit("http:" + httpSessionId).accepted();
        }
        if (!transports.isOpen(sessionId)) {
            cleanup(sessionId);
            return null;
        }
        if (accepted) {
            telemetry.frame("accepted");
            return message;
        }

        telemetry.frame("coarse_limited");
        int count = violationCount(sessionId);
        if (!transports.isOpen(sessionId)) {
            cleanup(sessionId);
            return null;
        }
        if (lifecycle || count >= violationThreshold) {
            cleanup(sessionId);
            transports.close(sessionId);
            telemetry.frame("closed");
            throw new MessagingException("inbound frame rate exceeded");
        }
        return null;
    }

    private Message<?> admitHeartbeat(Message<?> message, String sessionId) {
        if (sessionId == null || !transports.isOpen(sessionId)) {
            telemetry.frame("coarse_limited");
            return null;
        }
        if (heartbeatSessions.admit(sessionId).accepted()) {
            telemetry.frame("accepted");
            return message;
        }
        telemetry.frame("coarse_limited");
        cleanup(sessionId);
        transports.close(sessionId);
        telemetry.frame("closed");
        throw new MessagingException("inbound heartbeat rate exceeded");
    }

    private void cleanup(String sessionId) {
        if (sessionId == null) return;
        violations.remove(sessionId);
        sessions.remove(sessionId);
        lifecycleSessions.remove(sessionId);
        heartbeatSessions.remove(sessionId);
        // Identity buckets intentionally survive reconnects until their bounded TTL.
    }

    @EventListener public void disconnected(SessionDisconnectEvent event) { cleanup(event.getSessionId()); }
    @EventListener public void closed(WebSocketTransportRegistry.SocketClosed event) { cleanup(event.socketId()); }
    int trackedSocketCount() {
        return sessions.size() + lifecycleSessions.size() + heartbeatSessions.size() + violations.size();
    }
    int trackedIdentityCount() { return users.size() + lifecycleIdentities.size(); }

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
        lifecycleSessions.sweep(512);
        lifecycleIdentities.sweep(512);
        heartbeatSessions.sweep(512);
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
