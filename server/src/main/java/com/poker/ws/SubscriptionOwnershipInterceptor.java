package com.poker.ws;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/** Prevents a session from unsubscribing a subscription id it did not create. */
final class SubscriptionOwnershipInterceptor implements ChannelInterceptor {
    private final ConcurrentHashMap<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.SUBSCRIBE) {
            String subscriptionId = accessor.getSubscriptionId();
            if (subscriptionId != null && !subscriptionId.isBlank()) {
                subscriptions.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(subscriptionId);
            }
        } else if (command == StompCommand.UNSUBSCRIBE) {
            String subscriptionId = accessor.getSubscriptionId();
            Set<String> owned = subscriptions.get(sessionId);
            if (subscriptionId == null || owned == null || !owned.remove(subscriptionId)) {
                return null;
            }
        } else if (command == StompCommand.DISCONNECT || accessor.getMessageType() == SimpMessageType.DISCONNECT) {
            subscriptions.remove(sessionId);
        }
        return message;
    }
}
