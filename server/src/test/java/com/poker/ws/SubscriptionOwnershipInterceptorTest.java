package com.poker.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class SubscriptionOwnershipInterceptorTest {
    private final SubscriptionOwnershipInterceptor interceptor = new SubscriptionOwnershipInterceptor();

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

    private static Message<byte[]> frame(StompCommand command, String sessionId, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
