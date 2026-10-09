package com.poker.presence;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.ws.WebSocketTransportRegistry;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.web.socket.server.SessionRepositoryMessageInterceptor;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public final class StompPresenceListener {
    private final UserSocketRegistry sockets;
    private final RoomIdleCoordinator idle;

    public StompPresenceListener(
            UserSocketRegistry sockets,
            RoomIdleCoordinator idle) {
        this.sockets = sockets;
        this.idle = idle;
    }

    @EventListener
    public void connected(SessionConnectEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        if (headers.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUserPrincipal principal
                && headers.getSessionId() != null
                && sockets.register(
                                principal.userId(), headers.getSessionId(), httpSessionId(headers))
                        == UserSocketRegistry.Transition.ZERO_TO_ONE) {
            idle.connected(principal.userId());
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    @EventListener
    public void closed(WebSocketTransportRegistry.SocketClosed event) {
        disconnected(event.socketId());
    }

    private void disconnected(String socketId) {
        UserSocketRegistry.Removed removed = sockets.remove(socketId);
        if (removed.userId() != null
                && removed.transition() == UserSocketRegistry.Transition.ONE_TO_ZERO) {
            idle.disconnected(removed.userId());
        }
    }

    @EventListener
    public void sessionDeleted(SessionDeletedEvent event) {
        for (java.util.UUID userId : sockets.removeHttpSession(event.getSession().getId())) {
            idle.hardDisconnected(userId);
        }
    }

    private static String httpSessionId(StompHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        return attributes == null ? null : SessionRepositoryMessageInterceptor.getSessionId(attributes);
    }
}
