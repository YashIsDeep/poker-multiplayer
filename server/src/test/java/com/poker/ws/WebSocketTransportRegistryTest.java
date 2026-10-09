package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.hardening.HardeningProperties;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class WebSocketTransportRegistryTest {
    @Test
    void expiryDeadlineRevalidatesRefreshThenClosesEverySocketWhenSessionEnds() throws Exception {
        HardeningProperties properties = new HardeningProperties();
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        @SuppressWarnings("unchecked")
        SessionRepository<Session> sessions = mock(SessionRepository.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> deadlines = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            deadlines.add(invocation.getArgument(0));
            return mock(java.util.concurrent.ScheduledFuture.class);
        });
        MapSession initial = activeSession("http", Instant.now(), Duration.ofSeconds(1));
        MapSession refreshed = activeSession("http", Instant.now().plusSeconds(30), Duration.ofSeconds(1));
        when(sessions.findById("http")).thenReturn(initial, initial, refreshed, null);
        WebSocketTransportRegistry registry = new WebSocketTransportRegistry(
                properties, events, sessions, scheduler);
        WebSocketSession first = socket("a", UUID.randomUUID(), "http");
        WebSocketSession second = socket("b", UUID.randomUUID(), "http");

        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
        assertThat(deadlines).hasSize(1);
        deadlines.get(0).run();
        verify(first, never()).close(any());
        verify(second, never()).close(any());
        assertThat(deadlines).hasSize(2);

        deadlines.get(1).run();
        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.isOpen("a")).isFalse();
        assertThat(registry.isOpen("b")).isFalse();
    }

    @Test
    void expiryLookupFailureFailsClosedInsteadOfDisablingFutureEnforcement() throws Exception {
        HardeningProperties properties = new HardeningProperties();
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        @SuppressWarnings("unchecked")
        SessionRepository<Session> sessions = mock(SessionRepository.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> deadlines = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            deadlines.add(invocation.getArgument(0));
            return mock(java.util.concurrent.ScheduledFuture.class);
        });
        MapSession active = activeSession("http", Instant.now(), Duration.ofSeconds(1));
        when(sessions.findById("http"))
                .thenReturn(active)
                .thenThrow(new IllegalStateException("temporary database failure"));
        WebSocketTransportRegistry registry = new WebSocketTransportRegistry(
                properties, events, sessions, scheduler);
        WebSocketSession socket = socket("a", UUID.randomUUID(), "http");

        assertThat(registry.register(socket)).isTrue();
        assertThat(deadlines).hasSize(1);
        deadlines.getFirst().run();

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.isOpen("a")).isFalse();
        assertThat(deadlines).hasSize(1);
    }

    @Test
    void physicalSocketRequiresAuthenticatedIdentityAndHttpSessionBeforeStomp() throws Exception {
        WebSocketTransportRegistry registry = registry(2, 2, 32);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler handler = registry.decorate(delegate);
        WebSocketSession anonymous = socket("anonymous", null, "http");
        WebSocketSession sessionless = socket("sessionless", UUID.randomUUID(), null);
        handler.afterConnectionEstablished(anonymous);
        handler.afterConnectionEstablished(sessionless);
        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
        verify(sessionless).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(delegate);
        assertThat(registry.trackedSocketCount()).isZero();
    }

    @Test
    void physicalLimitsApplyBeforeConnectAndRejectedSocketsNeverReachDelegate() throws Exception {
        WebSocketTransportRegistry registry = registry(2, 2, 32);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler handler = registry.decorate(delegate);
        UUID user = UUID.randomUUID();
        handler.afterConnectionEstablished(socket("a", user, "http-a"));
        handler.afterConnectionEstablished(socket("b", user, "http-b"));
        WebSocketSession excessUser = socket("excess-user", user, "http-c");
        handler.afterConnectionEstablished(excessUser);
        handler.handleMessage(excessUser, new TextMessage("CONNECT\n\n\0"));
        verify(excessUser).close(CloseStatus.POLICY_VIOLATION);
        verify(delegate, never()).afterConnectionEstablished(excessUser);
        verify(delegate, never()).handleMessage(eq(excessUser), any());
        handler.afterConnectionEstablished(socket("c", UUID.randomUUID(), "shared-http"));
        handler.afterConnectionEstablished(socket("d", UUID.randomUUID(), "shared-http"));
        WebSocketSession excessHttp = socket("excess-http", UUID.randomUUID(), "shared-http");
        handler.afterConnectionEstablished(excessHttp);
        verify(excessHttp).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.trackedSocketCount()).isEqualTo(4);
    }

    @Test
    void forcedCloseIsIdempotentStopsDispatchAndCallbackReleasesQuota() throws Exception {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        HardeningProperties properties = new HardeningProperties();
        properties.setMaxSocketsPerUser(1);
        WebSocketTransportRegistry registry = new WebSocketTransportRegistry(properties, events);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler handler = registry.decorate(delegate);
        UUID user = UUID.randomUUID();
        WebSocketSession socket = socket("a", user, "http");
        handler.afterConnectionEstablished(socket);
        registry.close("a");
        registry.close("a");
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(events).publishEvent(new WebSocketTransportRegistry.SocketClosed("a"));
        assertThat(registry.isOpen("a")).isFalse();
        handler.handleMessage(socket, new TextMessage("late frame"));
        verify(delegate, never()).handleMessage(any(), any());
        handler.afterConnectionClosed(socket, CloseStatus.POLICY_VIOLATION);
        assertThat(registry.trackedSocketCount()).isZero();
        handler.afterConnectionEstablished(socket("b", user, "http"));
        assertThat(registry.isOpen("b")).isTrue();
    }

    @Test
    void transportFailureStillPublishesCleanupAndCloseCallbackReleasesQuota() throws Exception {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WebSocketTransportRegistry registry = new WebSocketTransportRegistry(new HardeningProperties(), events);
        WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));
        WebSocketSession socket = socket("broken", UUID.randomUUID(), "http");
        handler.afterConnectionEstablished(socket);
        doThrow(new IOException("already broken")).when(socket).close(CloseStatus.POLICY_VIOLATION);
        registry.close("broken");
        verify(events).publishEvent(new WebSocketTransportRegistry.SocketClosed("broken"));
        assertThat(registry.isOpen("broken")).isFalse();
        handler.afterConnectionClosed(socket, CloseStatus.SERVER_ERROR);
        assertThat(registry.trackedSocketCount()).isZero();
    }

    @Test
    void delegateSetupFailureClosesAndCleansUnconnectedSocket() throws Exception {
        WebSocketTransportRegistry registry = registry(1, 1, 32);
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler handler = registry.decorate(delegate);
        WebSocketSession socket = socket("broken", UUID.randomUUID(), "http");
        doThrow(new IllegalStateException("setup failed")).when(delegate).afterConnectionEstablished(socket);
        assertThatThrownBy(() -> handler.afterConnectionEstablished(socket)).isInstanceOf(IllegalStateException.class);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.trackedSocketCount()).isZero();
    }

    @Test
    void thousandsOfRejectedAndClosedSocketIdsCannotGrowTransportRegistry() throws Exception {
        WebSocketTransportRegistry registry = registry(1, 1, 8);
        for (int i = 0; i < 8; i++) assertThat(registry.register(socket("s-" + i, UUID.randomUUID(), "h-" + i))).isTrue();
        for (int i = 8; i < 2_000; i++) {
            WebSocketSession excess = socket("s-" + i, UUID.randomUUID(), "h-" + i);
            assertThat(registry.register(excess)).isFalse();
            verify(excess).close(CloseStatus.POLICY_VIOLATION);
        }
        assertThat(registry.trackedSocketCount()).isEqualTo(8);
        for (int i = 0; i < 8; i++) registry.remove("s-" + i);
        WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));
        for (int i = 0; i < 2_000; i++) {
            WebSocketSession churn = socket("churn-" + i, UUID.randomUUID(), "http-churn-" + i);
            handler.afterConnectionEstablished(churn);
            handler.afterConnectionClosed(churn, CloseStatus.NORMAL);
        }
        assertThat(registry.trackedSocketCount()).isZero();
    }

    private static WebSocketTransportRegistry registry(int user, int http, int total) {
        HardeningProperties properties = new HardeningProperties();
        properties.setMaxSocketsPerUser(user);
        properties.setMaxSocketsPerHttpSession(http);
        properties.setRegistryCap(total);
        return new WebSocketTransportRegistry(properties, event -> {});
    }

    private static WebSocketSession socket(String id, UUID user, String http) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        if (http != null) SessionRepositoryMessageInterceptor.setSessionId(attributes, http);
        when(session.getAttributes()).thenReturn(attributes);
        if (user != null) {
            CurrentUserPrincipal principal = mock(CurrentUserPrincipal.class);
            when(principal.userId()).thenReturn(user);
            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);
            when(session.getPrincipal()).thenReturn(authentication);
        }
        return session;
    }

    private static MapSession activeSession(String id, Instant lastAccessed, Duration maxInactive) {
        MapSession session = new MapSession(id);
        session.setLastAccessedTime(lastAccessed);
        session.setMaxInactiveInterval(maxInactive);
        return session;
    }
}
