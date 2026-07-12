package com.poker.ws;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.session.MapSession;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.stereotype.Component;

/** Bridges JDBC-backed logout to Spring Session's WebSocket close listener. */
@Component
public final class WebSocketSessionLogoutHandler implements LogoutHandler {
    private final ApplicationEventPublisher events;

    public WebSocketSessionLogoutHandler(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        String sessionId = request.getRequestedSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            events.publishEvent(new SessionDeletedEvent(this, new MapSession(sessionId)));
        }
    }
}
