package com.poker.room.application;

import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.RoomConfig;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.poker.presence.RoomPresenceReconciler;
import org.springframework.beans.factory.annotation.Autowired;

/** Commits through RoomManager, then publishes immutable projections outside locks. */
@Service
public final class RoomCommandService {
    private static final Logger log = LoggerFactory.getLogger(RoomCommandService.class);
    private final RoomManager roomManager;
    private final RoomEventSink eventSink;
    private final RoomPresenceReconciler presence;
    private final ScheduledGameplayCoordinator gameplay;

    public RoomCommandService(RoomManager roomManager, RoomEventSink eventSink) {
        this(roomManager, eventSink, null, null);
    }

    @Autowired
    public RoomCommandService(RoomManager roomManager, RoomEventSink eventSink,
            RoomPresenceReconciler presence, ScheduledGameplayCoordinator gameplay) {
        this.roomManager = Objects.requireNonNull(roomManager, "roomManager");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.presence = presence;
        this.gameplay = gameplay;
    }

    public RoomCommandResult create(MemberProfile profile, String name, int maxSeats) {
        return commit(roomManager.create(profile, name, maxSeats));
    }

    public RoomCommandResult create(MemberProfile profile, String name, int maxSeats, RoomConfig config) {
        return commit(roomManager.create(profile, name, maxSeats, config));
    }

    public RoomCommandResult join(MemberProfile profile, String joinCode) {
        return commit(roomManager.join(profile, joinCode));
    }

    public RoomCommandResult leave(UUID userId, UUID roomId) {
        return commit(roomManager.leave(userId, roomId));
    }

    public RoomCommandResult takeSeat(UUID userId, UUID roomId, int seat) {
        return commit(roomManager.takeSeat(userId, roomId, seat));
    }

    public RoomCommandResult takeSeat(UUID userId, UUID roomId, int seat, long buyIn) {
        return commit(roomManager.takeSeat(userId, roomId, seat, buyIn));
    }

    public RoomCommandResult stand(UUID userId, UUID roomId) {
        return commit(roomManager.stand(userId, roomId));
    }

    public RoomCommandResult roomSync(UUID userId, UUID roomId) {
        return commit(roomManager.roomSync(userId, roomId));
    }

    public RoomCommandResult stateSync(UUID userId) {
        RoomChange change = roomManager.stateSync(userId);
        return new RoomCommandResult(change, PublicationReport.none());
    }

    private RoomCommandResult commit(RoomChange change) {
        PublicationReport report;
        try {
            report = eventSink.publish(change);
        } catch (RuntimeException ex) {
            String correlationId = PublicationReport.newCorrelationId();
            log.warn("Room publication failed correlationId={} kind={} roomId={}",
                    correlationId, change.kind(), change.roomId(), ex);
            report = new PublicationReport(
                    1, java.util.List.of("PUBLISH_FAILED"), correlationId);
        }
        reconcilePresence(change);
        reconcileGameplay(change);
        return new RoomCommandResult(change, report == null ? PublicationReport.none() : report);
    }

    private void reconcilePresence(RoomChange change) {
        if (presence == null) return;
        try {
            presence.reconcile(change);
        } catch (RuntimeException ex) {
            log.error("Room presence reconciliation failed kind={} roomId={}",
                    change.kind(), change.roomId(), ex);
        }
    }

    private void reconcileGameplay(RoomChange change) {
        if (gameplay == null) return;
        try {
            gameplay.reconcile(change);
        } catch (RuntimeException ex) {
            log.error("Gameplay scheduling failed kind={} roomId={}",
                    change.kind(), change.roomId(), ex);
        }
    }
}
