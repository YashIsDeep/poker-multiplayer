package com.poker.room.application;

import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Commits through RoomManager, then publishes immutable projections outside locks. */
@Service
public final class RoomCommandService {
    private static final Logger log = LoggerFactory.getLogger(RoomCommandService.class);
    private final RoomManager roomManager;
    private final RoomEventSink eventSink;

    public RoomCommandService(RoomManager roomManager, RoomEventSink eventSink) {
        this.roomManager = Objects.requireNonNull(roomManager, "roomManager");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    public RoomCommandResult create(MemberProfile profile, String name, int maxSeats) {
        return commit(roomManager.create(profile, name, maxSeats));
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
        try {
            PublicationReport report = eventSink.publish(change);
            return new RoomCommandResult(change, report == null ? PublicationReport.none() : report);
        } catch (RuntimeException ex) {
            String correlationId = PublicationReport.newCorrelationId();
            log.warn("Room publication failed correlationId={} kind={} roomId={}",
                    correlationId, change.kind(), change.roomId(), ex);
            return new RoomCommandResult(
                    change,
                    new PublicationReport(1, java.util.List.of("PUBLISH_FAILED"), correlationId));
        }
    }
}
