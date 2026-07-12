package com.poker.presence;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.room.RoomManager;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public final class StompPresenceListener {
    private final UserSocketRegistry sockets;
    private final RoomManager rooms;
    private final RoomIdleCoordinator idle;

    public StompPresenceListener(
            UserSocketRegistry sockets,
            RoomManager rooms,
            RoomIdleCoordinator idle) {
        this.sockets = sockets;
        this.rooms = rooms;
        this.idle = idle;
    }

    @EventListener
    public void connected(SessionConnectedEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        if (headers.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUserPrincipal principal
                && headers.getSessionId() != null
                && sockets.register(principal.userId(), headers.getSessionId())
                        == UserSocketRegistry.Transition.ZERO_TO_ONE) {
            rooms.currentRoom(principal.userId()).ifPresent(idle::reconcile);
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        UserSocketRegistry.Removed removed = sockets.remove(event.getSessionId());
        if (removed.userId() != null
                && removed.transition() == UserSocketRegistry.Transition.ONE_TO_ZERO) {
            rooms.currentRoom(removed.userId()).ifPresent(idle::reconcile);
        }
    }
}
