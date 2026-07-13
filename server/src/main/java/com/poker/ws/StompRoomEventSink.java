package com.poker.ws;

import com.poker.room.RoomChange;
import com.poker.room.application.PublicationReport;
import com.poker.room.application.RoomEventSink;
import com.poker.ws.protocol.LobbySnapshot;
import com.poker.ws.protocol.RoomExitNotice;
import com.poker.ws.protocol.RoomSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Best-effort adapter; all independent enqueues are attempted after commit. */
@Component
public final class StompRoomEventSink implements RoomEventSink {
    private static final Logger log = LoggerFactory.getLogger(StompRoomEventSink.class);
    private final SimpMessagingTemplate messaging;
    private final RoomSnapshotMapper mapper;
    private final Clock clock;

    public StompRoomEventSink(SimpMessagingTemplate messaging, RoomSnapshotMapper mapper, Clock clock) {
        this.messaging = messaging;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public PublicationReport publish(RoomChange change) {
        if (change.kind() == RoomChange.Kind.STATE_SYNC) {
            return PublicationReport.none();
        }
        int attempted = 0;
        List<String> failures = new ArrayList<>();
        if (change.room() != null) {
            for (UUID recipient : change.recipients()) {
                attempted++;
                try {
                    long epoch = change.membershipEpochs().getOrDefault(recipient, 0L);
                    RoomSnapshot snapshot = mapper.forViewer(
                            change.room(), recipient, epoch, change.lobby().serverNow());
                    messaging.convertAndSendToUser(recipient.toString(), "/queue/room", snapshot);
                } catch (RuntimeException ex) {
                    failures.add("ROOM_RECIPIENT");
                }
            }
        }
        if (change.exitedUserId() != null && change.roomId() != null) {
            attempted++;
            try {
                RoomExitNotice notice = change.kind() == RoomChange.Kind.DISCONNECTED_MEMBER_EVICTED
                        ? mapper.disconnectedExit(
                                change.roomId(), change.exitedMembershipEpoch(), clock.instant())
                        : mapper.exit(
                                change.roomId(), change.exitedMembershipEpoch(), clock.instant());
                messaging.convertAndSendToUser(
                        change.exitedUserId().toString(), "/queue/room-events", notice);
            } catch (RuntimeException ex) {
                failures.add("ROOM_EXIT");
            }
        }
        if (change.kind() == RoomChange.Kind.IDLE_CLOSED && change.roomId() != null) {
            for (UUID recipient : change.recipients()) {
                attempted++;
                try {
                    RoomExitNotice notice = mapper.idleExit(change.roomId(),
                            change.membershipEpochs().getOrDefault(recipient, 0L), clock.instant());
                    messaging.convertAndSendToUser(recipient.toString(), "/queue/room-events", notice);
                } catch (RuntimeException ex) {
                    failures.add("ROOM_IDLE_EXIT");
                }
            }
        }
        if (change.kind() != RoomChange.Kind.ROOM_SYNC
                && change.kind() != RoomChange.Kind.GAME_CHANGED
                && change.kind() != RoomChange.Kind.KEEP_ALIVE
                && change.roomRevision() > 0) {
            attempted++;
            try {
                LobbySnapshot lobby = mapper.lobby(change.lobby());
                messaging.convertAndSend("/topic/lobby", lobby);
            } catch (RuntimeException ex) {
                failures.add("LOBBY");
            }
        }
        if (failures.isEmpty()) {
            return PublicationReport.success(attempted);
        }
        String correlationId = PublicationReport.newCorrelationId();
        log.warn("Room publication failures correlationId={} kind={} roomId={} failed={}",
                correlationId, change.kind(), change.roomId(), failures.size());
        return new PublicationReport(attempted, failures, correlationId);
    }
}
