package com.poker.ws;

import com.poker.room.RoomException;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandType;
import com.poker.ws.protocol.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/** Maps failures occurring before a controller method to a safe null-ID ack. */
@ControllerAdvice
public class StompErrorHandler {
    @MessageExceptionHandler({
            MessageConversionException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class})
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck invalidRequest(Exception ignored, Message<?> message) {
        return CommandAck.failure(null, commandFor(message), ErrorCode.INVALID_REQUEST);
    }

    @MessageExceptionHandler(RoomException.class)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck roomError(RoomException exception, Message<?> message) {
        ErrorCode error = switch (exception.error()) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case ROOM_FULL -> ErrorCode.ROOM_FULL;
            case ALREADY_IN_ANOTHER_ROOM -> ErrorCode.ALREADY_IN_ANOTHER_ROOM;
            case NOT_A_ROOM_MEMBER -> ErrorCode.NOT_A_ROOM_MEMBER;
            case SEAT_OUT_OF_RANGE -> ErrorCode.SEAT_OUT_OF_RANGE;
            case SEAT_OCCUPIED -> ErrorCode.SEAT_OCCUPIED;
            case ALREADY_SEATED -> ErrorCode.ALREADY_SEATED;
            case NOT_SEATED -> ErrorCode.NOT_SEATED;
            case JOIN_CODE_EXHAUSTED -> ErrorCode.JOIN_CODE_EXHAUSTED;
            default -> ErrorCode.INVALID_REQUEST;
        };
        return CommandAck.failure(null, commandFor(message), error);
    }

    private static CommandType commandFor(Message<?> message) {
        if (message == null) {
            return CommandType.STATE_SYNC;
        }
        Object destination = message.getHeaders().get("simpDestination");
        if (!(destination instanceof String value)) {
            return CommandType.STATE_SYNC;
        }
        return switch (value) {
            case "/app/ranked.join" -> CommandType.RANKED_JOIN;
            case "/app/ranked.cancel" -> CommandType.RANKED_CANCEL;
            case "/app/room.create" -> CommandType.ROOM_CREATE;
            case "/app/room.join" -> CommandType.ROOM_JOIN;
            case "/app/room.leave" -> CommandType.ROOM_LEAVE;
            case "/app/room.sync" -> CommandType.ROOM_SYNC;
            case "/app/room.keep-alive" -> CommandType.ROOM_KEEP_ALIVE;
            case "/app/room.update-settings" -> CommandType.ROOM_UPDATE_SETTINGS;
            case "/app/room.take-seat" -> CommandType.ROOM_TAKE_SEAT;
            case "/app/room.stand" -> CommandType.ROOM_STAND;
            case "/app/room.chat" -> CommandType.CHAT_SEND;
            case "/app/game.act" -> CommandType.GAME_ACT;
            case "/app/game.sit-out" -> CommandType.GAME_SIT_OUT;
            case "/app/game.sit-in" -> CommandType.GAME_SIT_IN;
            case "/app/game.rebuy" -> CommandType.GAME_REBUY;
            default -> CommandType.STATE_SYNC;
        };
    }
}
