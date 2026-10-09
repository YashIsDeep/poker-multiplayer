package com.poker.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.poker.hardening.HardeningProperties;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class SubscriptionOwnershipInterceptorTest {
    private final WebSocketTransportRegistry transports = mock(WebSocketTransportRegistry.class);
    private final SubscriptionOwnershipInterceptor interceptor = new SubscriptionOwnershipInterceptor(new HardeningProperties(), transports);

    @org.junit.jupiter.api.BeforeEach
    void openTransport() { when(transports.isOpen(org.mockito.ArgumentMatchers.anyString())).thenReturn(true); }

    @Test
    void permitsOnlyOneUnsubscribeForAnOwnedSessionSubscription() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "session-a", "sub-1"), null));
        assertNull(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "session-b", "sub-1"), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "session-a", "sub-1"), null));
        assertNull(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "session-a", "sub-1"), null));
    }

    @Test
    void disconnectClearsSubscriptionOwnership() {
        assertNotNull(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "session-a", "sub-1"), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.DISCONNECT, "session-a", null), null));
        assertNull(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "session-a", "sub-1"), null));
    }

    @Test
    void duplicateDestinationOrIdClosesTransportAndClearsOwnership() {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "a", "first", "/topic/lobby"), null);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "a", "second", "/topic/lobby"), null))
                .isInstanceOf(MessagingException.class);
        verify(transports).close("a");
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "b", "same", "/topic/lobby"), null);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "b", "same", "/user/queue/acks"), null))
                .isInstanceOf(MessagingException.class);
        verify(transports).close("b");
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    @Test
    void subscriptionCapClosesTransportAndUnsubscribeFreesCapacity() {
        HardeningProperties properties = new HardeningProperties();
        properties.setMaxSubscriptionsPerSocket(2);
        SubscriptionOwnershipInterceptor bounded = new SubscriptionOwnershipInterceptor(properties, transports);
        bounded.preSend(frame(StompCommand.SUBSCRIBE, "a", "1", "/topic/lobby"), null);
        bounded.preSend(frame(StompCommand.SUBSCRIBE, "a", "2", "/user/queue/acks"), null);
        bounded.preSend(frame(StompCommand.UNSUBSCRIBE, "a", "1"), null);
        assertThat(bounded.preSend(frame(StompCommand.SUBSCRIBE, "a", "3", "/user/queue/room"), null)).isNotNull();
        assertThat(bounded.subscriptionCount("a")).isEqualTo(2);
        assertThatThrownBy(() -> bounded.preSend(frame(StompCommand.SUBSCRIBE, "a", "4", "/user/queue/chat"), null))
                .isInstanceOf(MessagingException.class);
        verify(transports).close("a");
        assertThat(bounded.trackedSocketCount()).isZero();
    }

    @Test
    void thousandsOfDisconnectedAndUnknownSocketIdsDoNotRetainOwnership() {
        for (int i = 0; i < 5_000; i++) {
            String socket = "socket-" + i;
            interceptor.preSend(frame(StompCommand.SUBSCRIBE, socket, "sub"), null);
            interceptor.closed(new WebSocketTransportRegistry.SocketClosed(socket));
        }
        when(transports.isOpen(anyString())).thenReturn(false);
        for (int i = 0; i < 5_000; i++) {
            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "closed-" + i, "sub"), null)).isNull();
        }
        assertThat(interceptor.trackedSocketCount()).isZero();
    }

    private static Message<byte[]> frame(StompCommand command, String sessionId, String subscriptionId) {
        return frame(command, sessionId, subscriptionId, "/topic/lobby");
    }

    private static Message<byte[]> frame(StompCommand command, String sessionId, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (command == StompCommand.SUBSCRIBE) accessor.setDestination(destination);
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
