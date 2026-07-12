package com.poker.presence;

import com.poker.room.RoomChange;
import org.springframework.stereotype.Component;

@Component
public final class RoomPresenceReconciler {
    private final RoomIdleCoordinator idle;

    public RoomPresenceReconciler(RoomIdleCoordinator idle) {
        this.idle = idle;
    }

    public void reconcile(RoomChange change) {
        if (change.roomId() != null) idle.reconcile(change.roomId());
    }
}
