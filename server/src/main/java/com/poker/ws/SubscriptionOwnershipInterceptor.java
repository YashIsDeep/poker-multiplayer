package com.poker.ws;

import com.poker.hardening.HardeningProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Owns a bounded set of unique destinations and subscription IDs per socket. */
@Component
final class SubscriptionOwnershipInterceptor implements ChannelInterceptor {
    private final Map<String, Map<String, String>> subscriptions = new HashMap<>();
    private final WebSocketTransportRegistry transports;
    private final int maximum;

    SubscriptionOwnershipInterceptor(HardeningProperties properties, WebSocketTransportRegistry transports) {
        this.maximum = properties.getMaxSubscriptionsPerSocket();
        this.transports = transports;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        String sessionId = accessor.getSessionId();
        if (sessionId == null) return null;
        StompCommand command = accessor.getCommand();
        if (accessor.getMessageType() == SimpMessageType.DISCONNECT) {
            cleanup(sessionId);
            return message;
        }
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.UNSUBSCRIBE) return message;
        String subscriptionId = accessor.getSubscriptionId();
        boolean invalid = false;
        synchronized (subscriptions) {
            // Serialize registration with close cleanup so a racing frame cannot
            // recreate an orphaned entry after the transport's close event.
            if (!transports.isOpen(sessionId)) return null;
            if (command == StompCommand.SUBSCRIBE) {
                Map<String, String> owned = subscriptions.computeIfAbsent(sessionId, ignored -> new HashMap<>());
                String destination = accessor.getDestination();
                invalid = subscriptionId == null || subscriptionId.isBlank() || subscriptionId.length() > 128
                        || destination == null || owned.size() >= maximum || owned.containsKey(subscriptionId)
                        || owned.containsValue(destination);
                if (!invalid) owned.put(subscriptionId, destination);
                else subscriptions.remove(sessionId);
            } else {
                Map<String, String> owned = subscriptions.get(sessionId);
                if (subscriptionId == null || owned == null) return null;
                if (owned.remove(subscriptionId) == null) return null;
                if (owned.isEmpty()) subscriptions.remove(sessionId);
            }
        }
        if (invalid) {
            transports.close(sessionId);
            throw new MessagingException("subscription limit or duplicate subscription");
        }
        return message;
    }

    private void cleanup(String sessionId) {
        synchronized (subscriptions) { subscriptions.remove(sessionId); }
    }
    @EventListener public void disconnected(SessionDisconnectEvent event) { cleanup(event.getSessionId()); }
    @EventListener public void closed(WebSocketTransportRegistry.SocketClosed event) { cleanup(event.socketId()); }
    int subscriptionCount(String sessionId) {
        synchronized (subscriptions) {
            Map<String, String> owned = subscriptions.get(sessionId);
            return owned == null ? 0 : owned.size();
        }
    }
    int trackedSocketCount() { synchronized (subscriptions) { return subscriptions.size(); } }
}
