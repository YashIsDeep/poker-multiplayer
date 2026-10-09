package com.poker.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.poker.auth.CurrentUserPrincipal;
import com.poker.ws.WebSocketTransportRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;

class InboundFrameBudgetInterceptorTest {
    @Test
    void dropsCoarseExcessAndClosesOnlyAtWindowThreshold() {
        HardeningProperties properties = new HardeningProperties();
        properties.setFrameRate(1);
        properties.setFrameBurst(1);
        properties.setCoarseViolationThreshold(3);
        AtomicLong ticker = new AtomicLong();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), openTransports(), ticker::get);
        MessageChannel channel = mock(MessageChannel.class);
        Message<byte[]> message = applicationMessage("session-1");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        assertThat(interceptor.preSend(message, channel)).isNull();
        assertThat(interceptor.preSend(message, channel)).isNull();
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void violationWindowResets() {
        HardeningProperties properties = new HardeningProperties();
        properties.setFrameRate(1);
        properties.setFrameBurst(1);
        properties.setCoarseViolationThreshold(2);
        properties.setCoarseViolationWindow(java.time.Duration.ofSeconds(10));
        AtomicLong ticker = new AtomicLong();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), openTransports(), ticker::get);
        MessageChannel channel = mock(MessageChannel.class);
        Message<byte[]> message = applicationMessage("session-1");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        assertThat(interceptor.preSend(message, channel)).isNull();
        ticker.set(java.time.Duration.ofSeconds(11).toNanos());
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void coarseBudgetIsSharedAcrossSessionsForOneUser() {
        HardeningProperties properties = new HardeningProperties();
        properties.setFrameRate(1);
        properties.setFrameBurst(1);
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), openTransports(), () -> 0L);
        CurrentUserPrincipal principal = mock(CurrentUserPrincipal.class);
        when(principal.userId()).thenReturn(UUID.randomUUID());
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        MessageChannel channel = mock(MessageChannel.class);

        assertThat(interceptor.preSend(
                applicationMessage("session-1", authentication), channel)).isNotNull();
        assertThat(interceptor.preSend(
                applicationMessage("session-2", authentication), channel)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP", "SUBSCRIBE", "UNSUBSCRIBE"})
    void lifecycleExcessClosesTransportAndClearsSocketState(StompCommand command) {
        HardeningProperties properties = new HardeningProperties();
        properties.setLifecycleFrameBurst(1);
        WebSocketTransportRegistry transports = openTransports();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), transports, () -> 0L);
        Message<byte[]> frame = lifecycle(command, "socket", null, null);
        assertThat(interceptor.preSend(frame, null)).isSameAs(frame);
        assertThatThrownBy(() -> interceptor.preSend(frame, null)).isInstanceOf(MessagingException.class);
        verify(transports).close("socket");
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    @Test
    void lifecycleUserAndHttpBudgetsSurviveSocketRotation() {
        HardeningProperties properties = new HardeningProperties();
        properties.setLifecycleFrameBurst(1);
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), openTransports(), () -> 0L);
        Authentication identity = authentication(UUID.randomUUID());
        assertThat(interceptor.preSend(lifecycle(StompCommand.CONNECT, "a", identity, "http-a"), null)).isNotNull();
        interceptor.closed(new WebSocketTransportRegistry.SocketClosed("a"));
        assertThatThrownBy(() -> interceptor.preSend(lifecycle(StompCommand.CONNECT, "b", identity, "http-b"), null))
                .isInstanceOf(MessagingException.class);
        assertThatThrownBy(() -> interceptor.preSend(lifecycle(StompCommand.CONNECT, "c", authentication(UUID.randomUUID()), "http-a"), null))
                .isInstanceOf(MessagingException.class);
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    @Test
    void heartbeatBudgetClosesTransportBeforeLaterInterceptorsCanDoRepositoryWork() {
        HardeningProperties properties = new HardeningProperties();
        properties.setHeartbeatFrameRate(0.01);
        properties.setHeartbeatFrameBurst(2);
        WebSocketTransportRegistry transports = openTransports();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), transports, () -> 0L);
        Message<byte[]> heartbeat = heartbeat("socket");

        assertThat(interceptor.preSend(heartbeat, null)).isSameAs(heartbeat);
        assertThat(interceptor.preSend(heartbeat, null)).isSameAs(heartbeat);
        assertThatThrownBy(() -> interceptor.preSend(heartbeat, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("heartbeat");
        verify(transports).close("socket");
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    @Test
    void thousandsOfLifecycleIdentitiesRemainCappedAndDisconnectedSocketsDoNotAccumulate() {
        HardeningProperties properties = new HardeningProperties();
        properties.setRegistryCap(16);
        AtomicLong ticker = new AtomicLong();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), openTransports(), ticker::get);
        for (int i = 0; i < 5_000; i++) {
            String socket = "s-" + i;
            try {
                interceptor.preSend(lifecycle(StompCommand.CONNECT, socket, authentication(UUID.randomUUID()), "h-" + i), null);
            } catch (MessagingException expectedWhenFull) {
                assertThat(i).isGreaterThanOrEqualTo(8);
            }
            interceptor.closed(new WebSocketTransportRegistry.SocketClosed(socket));
        }
        assertThat(interceptor.trackedSocketCount()).isZero();
        assertThat(interceptor.trackedIdentityCount()).isEqualTo(16);
        ticker.set(properties.getRegistryTtl().plusSeconds(1).toNanos());
        interceptor.sweepExpiredEntries();
        assertThat(interceptor.trackedIdentityCount()).isZero();
    }

    @Test
    void framesQueuedAfterCloseCannotRecreateSocketBudgets() {
        WebSocketTransportRegistry transports = mock(WebSocketTransportRegistry.class);
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                new HardeningProperties(), new AbuseTelemetry(new SimpleMeterRegistry()), transports, () -> 0L);
        for (int i = 0; i < 5_000; i++) {
            assertThat(interceptor.preSend(lifecycle(StompCommand.SUBSCRIBE, "closed-" + i, null, null), null)).isNull();
        }
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    private static WebSocketTransportRegistry openTransports() {
        WebSocketTransportRegistry transports = mock(WebSocketTransportRegistry.class);
        when(transports.isOpen(anyString())).thenReturn(true);
        return transports;
    }

    private static Authentication authentication(UUID user) {
        CurrentUserPrincipal principal = mock(CurrentUserPrincipal.class);
        when(principal.userId()).thenReturn(user);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        return authentication;
    }

    private static Message<byte[]> lifecycle(StompCommand command, String socket, Authentication identity, String http) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(socket);
        if (identity != null) headers.setUser(identity);
        if (http != null) {
            Map<String, Object> attributes = new HashMap<>();
            SessionRepositoryMessageInterceptor.setSessionId(attributes, http);
            headers.setSessionAttributes(attributes);
        }
        if (command == StompCommand.SUBSCRIBE) {
            headers.setSubscriptionId("subscription");
            headers.setDestination("/topic/lobby");
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static Message<byte[]> applicationMessage(String sessionId) {
        return applicationMessage(sessionId, null);
    }

    private static Message<byte[]> heartbeat(String sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        headers.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[] {'\n'}, headers.getMessageHeaders());
    }

    private static Message<byte[]> applicationMessage(
            String sessionId,
            Authentication authentication) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setDestination("/app/state.sync");
        if (authentication != null) headers.setUser(authentication);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
