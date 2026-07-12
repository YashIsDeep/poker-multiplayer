package com.poker.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;

class ActiveWebSocketSessionInterceptorTest {
    @Test
    void rejectsAndPublishesClosureWhenHandshakeSessionHasEnded() {
        MapSessionRepository sessions = new MapSessionRepository(new ConcurrentHashMap<>());
        MapSession session = new MapSession();
        sessions.save(session);
        List<ApplicationEvent> events = new ArrayList<>();
        ActiveWebSocketSessionInterceptor interceptor =
                new ActiveWebSocketSessionInterceptor(sessions, event -> events.add((ApplicationEvent) event));
        Message<byte[]> frame = frame(session.getId());

        assertDoesNotThrow(() -> interceptor.preSend(frame, null));
        sessions.deleteById(session.getId());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(frame, null));
        assertEquals(1, events.stream().filter(SessionDeletedEvent.class::isInstance).count());
    }

    private static Message<byte[]> frame(String httpSessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("websocket-session");
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        SessionRepositoryMessageInterceptor.setSessionId(accessor.getSessionAttributes(), httpSessionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
