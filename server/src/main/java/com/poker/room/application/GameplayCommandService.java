package com.poker.room.application;

import com.poker.room.*;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class GameplayCommandService {
    private static final Logger log = LoggerFactory.getLogger(GameplayCommandService.class);
    private final RoomManager rooms;
    private final RoomEventSink events;
    private final ScheduledGameplayCoordinator coordinator;

    public GameplayCommandService(RoomManager rooms, RoomEventSink events, ScheduledGameplayCoordinator coordinator) {
        this.rooms = Objects.requireNonNull(rooms); this.events = Objects.requireNonNull(events); this.coordinator = Objects.requireNonNull(coordinator);
    }
    public RoomCommandResult act(UUID user, UUID room, long seq, GameAction action, Long amount) { return commit(rooms.act(user, room, seq, action, amount)); }
    public RoomCommandResult sitOut(UUID user, UUID room) { return commit(rooms.sitOut(user, room)); }
    public RoomCommandResult sitIn(UUID user, UUID room) { return commit(rooms.sitIn(user, room)); }
    public RoomCommandResult rebuy(UUID user, UUID room, long amount) { return commit(rooms.rebuy(user, room, amount)); }

    private RoomCommandResult commit(RoomChange change) {
        PublicationReport report;
        try {
            report = events.publish(change);
        } catch (RuntimeException ex) {
            log.warn("Gameplay publication failed roomId={} revision={}",
                    change.roomId(), change.roomRevision(), ex);
            report = PublicationReport.none();
        }
        try {
            coordinator.reconcile(change);
        } catch (RuntimeException ex) {
            log.error("Gameplay scheduling failed roomId={} revision={}",
                    change.roomId(), change.roomRevision(), ex);
        }
        return new RoomCommandResult(change, report == null ? PublicationReport.none() : report);
    }
}
