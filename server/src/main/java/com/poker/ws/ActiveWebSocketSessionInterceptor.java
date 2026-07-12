package com.poker.ws;

import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;
import org.springframework.stereotype.Component;

/** Rejects frames whose handshake HTTP session has since ended. */
@Component
public final class ActiveWebSocketSessionInterceptor implements ChannelInterceptor {
    private final SessionRepository<? extends Session> sessions;
    private final ApplicationEventPublisher events;

    public ActiveWebSocketSessionInterceptor(
            SessionRepository<? extends Session> sessions,
            ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.events = events;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.OTHER) {
            return message;
        }
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        String sessionId = attributes == null
                ? null
                : SessionRepositoryMessageInterceptor.getSessionId(attributes);
        if (sessionId == null || sessions.findById(sessionId) == null) {
            if (sessionId != null) {
                events.publishEvent(new SessionDeletedEvent(this, new MapSession(sessionId)));
            }
            throw new AccessDeniedException("HTTP session has ended");
        }
        return message;
    }
}
