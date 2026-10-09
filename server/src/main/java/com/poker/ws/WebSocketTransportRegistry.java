package com.poker.ws;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.hardening.HardeningProperties;
import com.poker.presence.UserSocketRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.Authentication;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Bounds physical connections, including sockets that never send STOMP CONNECT. */
@Component
public final class WebSocketTransportRegistry {
    private final Map<String, Transport> transports = new ConcurrentHashMap<>();
    private final Map<String, ExpirationMonitor> expirationMonitors = new ConcurrentHashMap<>();
    // Physical connection quotas are separate from the STOMP presence index.
    private final UserSocketRegistry quotas;
    private final ApplicationEventPublisher events;
    private final SessionRepository<? extends Session> sessions;
    private final TaskScheduler scheduler;

    @Autowired
    public WebSocketTransportRegistry(
            HardeningProperties properties,
            ApplicationEventPublisher events,
            SessionRepository<? extends Session> sessions,
            @Qualifier("gameplayTaskScheduler") TaskScheduler scheduler) {
        quotas = new UserSocketRegistry(properties);
        this.events = events;
        this.sessions = sessions;
        this.scheduler = scheduler;
    }

    /** Unit-test constructor for transport quota behavior without a session backend. */
    WebSocketTransportRegistry(HardeningProperties properties, ApplicationEventPublisher events) {
        this(properties, events, null, null);
    }

    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (!register(session)) return;
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception failure) {
                    close(session.getId());
                    remove(session.getId());
                    throw failure;
                }
            }

            @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                if (isOpen(session.getId())) super.handleMessage(session, message);
            }

            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                try {
                    super.afterConnectionClosed(session, status);
                } finally {
                    remove(session.getId());
                }
            }
        };
    }

    boolean register(WebSocketSession session) throws IOException {
        String httpSessionId = SessionRepositoryMessageInterceptor.getSessionId(session.getAttributes());
        if (!(session.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof CurrentUserPrincipal principal)
                || httpSessionId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return false;
        }
        Session backingSession = sessions == null ? null : sessions.findById(httpSessionId);
        if (sessions != null && backingSession == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return false;
        }
        try {
            synchronized (quotas) {
                quotas.register(principal.userId(), session.getId(), httpSessionId);
                transports.put(session.getId(), new Transport(session, httpSessionId, new AtomicBoolean()));
                if (sessions != null) {
                    ExpirationMonitor monitor = expirationMonitors.computeIfAbsent(
                            httpSessionId, ignored -> new ExpirationMonitor());
                    monitor.socketIds.add(session.getId());
                    scheduleValidation(httpSessionId, monitor, backingSession);
                }
            }
            return true;
        } catch (IllegalStateException exceeded) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return false;
        }
    }

    public boolean isOpen(String socketId) {
        Transport transport = socketId == null ? null : transports.get(socketId);
        return transport != null && !transport.closing().get() && transport.session().isOpen();
    }

    int trackedSocketCount() { return transports.size(); }

    /** Actively closes the transport; a channel exception alone is not a close guarantee. */
    public void close(String socketId) {
        Transport transport = socketId == null ? null : transports.get(socketId);
        if (transport == null || !transport.closing().compareAndSet(false, true)) return;
        try {
            transport.session().close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // The container's connection-closed callback still performs final cleanup.
        } finally {
            events.publishEvent(new SocketClosed(socketId));
        }
    }

    void remove(String socketId) {
        synchronized (quotas) {
            Transport removed = transports.remove(socketId);
            quotas.remove(socketId);
            if (removed != null) removeFromExpirationMonitor(removed.httpSessionId(), socketId);
        }
        events.publishEvent(new SocketClosed(socketId));
    }

    private void scheduleValidation(String httpSessionId, ExpirationMonitor monitor, Session session) {
        Duration maxInactive = session.getMaxInactiveInterval();
        if (maxInactive == null || maxInactive.isNegative()) return;
        Instant deadline = session.getLastAccessedTime().plus(maxInactive).plusMillis(1);
        synchronized (monitor) {
            if (expirationMonitors.get(httpSessionId) != monitor || monitor.scheduled != null) return;
            monitor.scheduled = scheduler.schedule(() -> validateSession(httpSessionId, monitor), deadline);
        }
    }

    private void validateSession(String httpSessionId, ExpirationMonitor monitor) {
        synchronized (monitor) {
            if (expirationMonitors.get(httpSessionId) != monitor) return;
            monitor.scheduled = null;
        }
        Session session;
        try {
            session = sessions.findById(httpSessionId);
        } catch (RuntimeException lookupFailure) {
            // A socket whose backing session cannot be validated must not keep
            // receiving private publications with no future expiry check.
            closeMonitoredTransports(monitor);
            return;
        }
        if (session == null || session.isExpired()) {
            // Mark every transport as closing before returning so concurrent
            // inbound dispatch and broker writes cannot treat it as active.
            closeMonitoredTransports(monitor);
            return;
        }
        scheduleValidation(httpSessionId, monitor, session);
    }

    private void closeMonitoredTransports(ExpirationMonitor monitor) {
        for (String socketId : Set.copyOf(monitor.socketIds)) close(socketId);
    }

    private void removeFromExpirationMonitor(String httpSessionId, String socketId) {
        ExpirationMonitor monitor = expirationMonitors.get(httpSessionId);
        if (monitor == null) return;
        monitor.socketIds.remove(socketId);
        if (!monitor.socketIds.isEmpty() || !expirationMonitors.remove(httpSessionId, monitor)) return;
        synchronized (monitor) {
            if (monitor.scheduled != null) monitor.scheduled.cancel(false);
            monitor.scheduled = null;
        }
    }

    public record SocketClosed(String socketId) {}
    private record Transport(WebSocketSession session, String httpSessionId, AtomicBoolean closing) {}
    private static final class ExpirationMonitor {
        private final Set<String> socketIds = ConcurrentHashMap.newKeySet();
        private ScheduledFuture<?> scheduled;
    }
}
