package com.poker.ws;

import com.poker.hardening.AdmittedCommand;
import com.poker.room.GameAction;
import com.poker.room.GameError;
import com.poker.room.GameException;
import com.poker.room.GameState;
import com.poker.room.RoomError;
import com.poker.room.RoomException;
import com.poker.room.application.GameplayCommandService;
import com.poker.room.application.RoomCommandResult;
import com.poker.ws.protocol.ActRequest;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandEnvelope;
import com.poker.ws.protocol.CommandType;
import com.poker.ws.protocol.ErrorCode;
import com.poker.ws.protocol.RebuyRequest;
import com.poker.ws.protocol.SitInRequest;
import com.poker.ws.protocol.SitOutRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
public class GameplayMessageController {
    private static final Logger log = LoggerFactory.getLogger(GameplayMessageController.class);

    private final GameplayCommandService service;

    public GameplayMessageController(GameplayCommandService service) {
        this.service = service;
    }

    @MessageMapping("/game.act")
    @AdmittedCommand(CommandType.GAME_ACT)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck act(
            @Valid @Payload CommandEnvelope<ActRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.GAME_ACT, principal, user -> {
            ActRequest request = envelope.payload();
            return ack(envelope.requestId(), CommandType.GAME_ACT, service.act(
                    user.userId(), request.roomId(), request.actionSeq(),
                    parseAction(request.action()), request.amount()));
        });
    }

    @MessageMapping("/game.sit-out")
    @AdmittedCommand(CommandType.GAME_SIT_OUT)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck sitOut(
            @Valid @Payload CommandEnvelope<SitOutRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.GAME_SIT_OUT, principal,
                user -> ack(envelope.requestId(), CommandType.GAME_SIT_OUT,
                        service.sitOut(user.userId(), envelope.payload().roomId())));
    }

    @MessageMapping("/game.sit-in")
    @AdmittedCommand(CommandType.GAME_SIT_IN)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck sitIn(
            @Valid @Payload CommandEnvelope<SitInRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.GAME_SIT_IN, principal,
                user -> ack(envelope.requestId(), CommandType.GAME_SIT_IN,
                        service.sitIn(user.userId(), envelope.payload().roomId())));
    }

    @MessageMapping("/game.rebuy")
    @AdmittedCommand(CommandType.GAME_REBUY)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck rebuy(
            @Valid @Payload CommandEnvelope<RebuyRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), CommandType.GAME_REBUY, principal,
                user -> ack(envelope.requestId(), CommandType.GAME_REBUY,
                        service.rebuy(
                                user.userId(), envelope.payload().roomId(),
                                envelope.payload().amount())));
    }

    private CommandAck execute(
            String requestId,
            CommandType command,
            Principal principal,
            UserOperation operation) {
        try {
            return operation.run(CurrentStompUser.require(principal));
        } catch (UnauthenticatedStompException ex) {
            return CommandAck.failure(requestId, command, ErrorCode.UNAUTHENTICATED);
        } catch (RoomException ex) {
            return CommandAck.failure(requestId, command, map(ex.error()));
        } catch (GameException ex) {
            return CommandAck.failure(requestId, command, ErrorCode.valueOf(ex.error().name()));
        } catch (RuntimeException ex) {
            String correlationId = UUID.randomUUID().toString();
            log.warn("Unexpected gameplay command failure correlationId={} command={}",
                    correlationId, command, ex);
            return CommandAck.failure(requestId, command, ErrorCode.INTERNAL_ERROR);
        }
    }

    private static GameAction parseAction(String rawAction) {
        try {
            return "raiseTo".equals(rawAction)
                    ? GameAction.RAISE_TO
                    : GameAction.valueOf(rawAction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new GameException(GameError.ACTION_NOT_LEGAL, ex);
        }
    }

    private static CommandAck ack(
            String requestId,
            CommandType command,
            RoomCommandResult result) {
        GameState game = result.change().room().game();
        return CommandAck.gameSuccess(
                requestId,
                command,
                result.change().roomId(),
                result.change().roomRevision(),
                result.change().lobbyRevision(),
                game.actionSeq());
    }

    private static ErrorCode map(RoomError error) {
        return switch (error) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case NOT_A_ROOM_MEMBER -> ErrorCode.NOT_A_ROOM_MEMBER;
            case INVALID_BUY_IN -> ErrorCode.INVALID_BUY_IN;
            case NOT_ROOM_ADMIN -> ErrorCode.NOT_ROOM_ADMIN;
            case SETTINGS_NOT_EDITABLE -> ErrorCode.SETTINGS_NOT_EDITABLE;
            case HAND_IN_PROGRESS -> ErrorCode.HAND_IN_PROGRESS;
            default -> ErrorCode.INVALID_REQUEST;
        };
    }

    @FunctionalInterface
    private interface UserOperation {
        CommandAck run(CurrentStompUser user);
    }
}
