package com.poker.ws;

import com.poker.room.RoomException;
import com.poker.room.application.RoomCommandResult;
import com.poker.room.application.RoomCommandService;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandEnvelope;
import com.poker.ws.protocol.CommandType;
import com.poker.ws.protocol.CreateRoomRequest;
import com.poker.ws.protocol.ErrorCode;
import com.poker.ws.protocol.JoinRoomRequest;
import com.poker.ws.protocol.LeaveRoomRequest;
import com.poker.ws.protocol.StandRequest;
import com.poker.ws.protocol.StateSyncView;
import com.poker.ws.protocol.SyncRoomRequest;
import com.poker.ws.protocol.SyncStateRequest;
import com.poker.ws.protocol.TakeSeatRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

@Controller
@Validated
public class RoomMessageController {
    private static final Logger log = LoggerFactory.getLogger(RoomMessageController.class);
    private final RoomCommandService service;
    private final RoomSnapshotMapper mapper;

    public RoomMessageController(RoomCommandService service, RoomSnapshotMapper mapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @MessageMapping("/state.sync")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck stateSync(
            @Valid @Payload CommandEnvelope<SyncStateRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.STATE_SYNC, principal, user -> {
            RoomCommandResult result = service.stateSync(user.userId());
            StateSyncView state = mapper.stateSync(
                    result.change().lobby(),
                    result.change().room(),
                    user.userId(),
                    result.change().membershipEpochs().getOrDefault(user.userId(), 0L));
            return CommandAck.stateSuccess(envelope.requestId(), state);
        });
    }

    @MessageMapping("/room.create")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck create(
            @Valid @Payload CommandEnvelope<CreateRoomRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_CREATE, principal, user -> {
            RoomCommandResult result = service.create(user.profile(), envelope.payload().name(), envelope.payload().maxSeats());
            return ack(envelope.requestId(), CommandType.ROOM_CREATE, result);
        });
    }

    @MessageMapping("/room.join")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck join(
            @Valid @Payload CommandEnvelope<JoinRoomRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_JOIN, principal, user -> {
            RoomCommandResult result = service.join(user.profile(), envelope.payload().joinCode());
            return ack(envelope.requestId(), CommandType.ROOM_JOIN, result);
        });
    }

    @MessageMapping("/room.leave")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck leave(
            @Valid @Payload CommandEnvelope<LeaveRoomRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_LEAVE, principal, user -> {
            RoomCommandResult result = service.leave(user.userId(), envelope.payload().roomId());
            return ack(envelope.requestId(), CommandType.ROOM_LEAVE, result);
        });
    }

    @MessageMapping("/room.sync")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck roomSync(
            @Valid @Payload CommandEnvelope<SyncRoomRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_SYNC, principal, user -> {
            RoomCommandResult result = service.roomSync(user.userId(), envelope.payload().roomId());
            return ack(envelope.requestId(), CommandType.ROOM_SYNC, result);
        });
    }

    @MessageMapping("/room.take-seat")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck takeSeat(
            @Valid @Payload CommandEnvelope<TakeSeatRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_TAKE_SEAT, principal, user -> {
            RoomCommandResult result = service.takeSeat(
                    user.userId(), envelope.payload().roomId(), envelope.payload().seat());
            return ack(envelope.requestId(), CommandType.ROOM_TAKE_SEAT, result);
        });
    }

    @MessageMapping("/room.stand")
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck stand(
            @Valid @Payload CommandEnvelope<StandRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.ROOM_STAND, principal, user -> {
            RoomCommandResult result = service.stand(user.userId(), envelope.payload().roomId());
            return ack(envelope.requestId(), CommandType.ROOM_STAND, result);
        });
    }

    private CommandAck execute(
            String requestId,
            CommandType command,
            Principal principal,
            UserOperation operation) {
        try {
            return operation.apply(CurrentStompUser.require(principal));
        } catch (UnauthenticatedStompException ex) {
            return CommandAck.failure(requestId, command, ErrorCode.UNAUTHENTICATED);
        } catch (RoomException ex) {
            return CommandAck.failure(requestId, command, mapError(ex));
        } catch (RuntimeException ex) {
            String correlationId = java.util.UUID.randomUUID().toString();
            log.warn("Unexpected STOMP command failure correlationId={} command={}", correlationId, command, ex);
            return CommandAck.failure(requestId, command, ErrorCode.INTERNAL_ERROR);
        }
    }

    private static CommandAck ack(String requestId, CommandType command, RoomCommandResult result) {
        return CommandAck.success(
                requestId,
                command,
                result.change().roomId(),
                result.change().roomRevision(),
                result.change().lobbyRevision());
    }

    private static ErrorCode mapError(RoomException ex) {
        return switch (ex.error()) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case ROOM_FULL -> ErrorCode.ROOM_FULL;
            case ALREADY_IN_ANOTHER_ROOM -> ErrorCode.ALREADY_IN_ANOTHER_ROOM;
            case NOT_A_ROOM_MEMBER -> ErrorCode.NOT_A_ROOM_MEMBER;
            case SEAT_OUT_OF_RANGE -> ErrorCode.SEAT_OUT_OF_RANGE;
            case SEAT_OCCUPIED -> ErrorCode.SEAT_OCCUPIED;
            case ALREADY_SEATED -> ErrorCode.ALREADY_SEATED;
            case NOT_SEATED -> ErrorCode.NOT_SEATED;
            case JOIN_CODE_EXHAUSTED -> ErrorCode.JOIN_CODE_EXHAUSTED;
            case INVALID_ROOM_NAME, INVALID_MAX_SEATS, INVALID_JOIN_CODE -> ErrorCode.INVALID_REQUEST;
            case INTERNAL_ERROR -> ErrorCode.INTERNAL_ERROR;
        };
    }

    @FunctionalInterface
    private interface UserOperation {
        CommandAck apply(CurrentStompUser user);
    }
}
