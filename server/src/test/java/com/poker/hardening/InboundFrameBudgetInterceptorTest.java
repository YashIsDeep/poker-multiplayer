package com.poker.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.poker.auth.CurrentUserPrincipal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;

class InboundFrameBudgetInterceptorTest {
    @Test
    void dropsCoarseExcessAndClosesOnlyAtWindowThreshold() {
        HardeningProperties properties = new HardeningProperties();
        properties.setFrameRate(1);
        properties.setFrameBurst(1);
        properties.setCoarseViolationThreshold(3);
        AtomicLong ticker = new AtomicLong();
        InboundFrameBudgetInterceptor interceptor = new InboundFrameBudgetInterceptor(
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), ticker::get);
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
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), ticker::get);
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
                properties, new AbuseTelemetry(new SimpleMeterRegistry()), () -> 0L);
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

    private static Message<byte[]> applicationMessage(String sessionId) {
        return applicationMessage(sessionId, null);
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
