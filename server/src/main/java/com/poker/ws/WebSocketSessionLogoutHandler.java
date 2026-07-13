package com.poker.ws;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.session.MapSession;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.stereotype.Component;
import com.poker.presence.RoomIdleCoordinator;
import com.poker.presence.UserSocketRegistry;

/** Bridges JDBC-backed logout to Spring Session's WebSocket close listener. */
@Component
public final class WebSocketSessionLogoutHandler implements LogoutHandler {
    private final ApplicationEventPublisher events;
    private final UserSocketRegistry sockets;
    private final RoomIdleCoordinator presence;

    public WebSocketSessionLogoutHandler(
            ApplicationEventPublisher events,
            UserSocketRegistry sockets,
            RoomIdleCoordinator presence) {
        this.events = events;
        this.sockets = sockets;
        this.presence = presence;
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        String sessionId = request.getRequestedSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            for (java.util.UUID userId : sockets.removeHttpSession(sessionId)) {
                presence.hardDisconnected(userId);
            }
            events.publishEvent(new SessionDeletedEvent(this, new MapSession(sessionId)));
        }
    }
}
