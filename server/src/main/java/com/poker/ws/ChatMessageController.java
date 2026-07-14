package com.poker.ws;

import com.poker.hardening.AdmittedCommand;
import com.poker.room.RoomChatTarget;
import com.poker.room.RoomException;
import com.poker.room.RoomManager;
import com.poker.ws.protocol.ChatMessage;
import com.poker.ws.protocol.ChatRequest;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandEnvelope;
import com.poker.ws.protocol.CommandType;
import com.poker.ws.protocol.ErrorCode;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
public class ChatMessageController {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

    private final RoomManager roomManager;
    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public ChatMessageController(RoomManager roomManager, SimpMessagingTemplate messaging, Clock clock) {
        this.roomManager = roomManager;
        this.messaging = messaging;
        this.clock = clock;
    }

    @MessageMapping("/room.chat")
    @AdmittedCommand(CommandType.CHAT_SEND)
    @SendToUser(destinations = "/queue/acks", broadcast = false)
    public CommandAck chat(
            @Valid @Payload CommandEnvelope<ChatRequest> envelope,
            Principal principal) {
        return execute(envelope.requestId(), principal, envelope.payload());
    }

    private CommandAck execute(String requestId, Principal principal, ChatRequest request) {
        try {
            CurrentStompUser user = CurrentStompUser.require(principal);
            RoomChatTarget target = roomManager.chatTarget(user.userId(), request.roomId());
            ChatMessage message = new ChatMessage(
                    UUID.randomUUID(),
                    target.roomId(),
                    target.sender().userId(),
                    target.sender().displayName(),
                    request.message(),
                    clock.instant());
            for (UUID recipient : target.recipients()) {
                messaging.convertAndSendToUser(recipient.toString(), "/queue/chat", message);
            }
            return new CommandAck(
                    requestId, CommandType.CHAT_SEND, true, null,
                    target.roomId(), null, null, null, null, null);
        } catch (UnauthenticatedStompException ex) {
            return CommandAck.failure(requestId, CommandType.CHAT_SEND, ErrorCode.UNAUTHENTICATED);
        } catch (RoomException ex) {
            return CommandAck.failure(requestId, CommandType.CHAT_SEND, mapError(ex));
        } catch (RuntimeException ex) {
            String correlationId = UUID.randomUUID().toString();
            log.warn("Unexpected chat command failure correlationId={} roomId={}",
                    correlationId, request.roomId(), ex);
            return CommandAck.failure(requestId, CommandType.CHAT_SEND, ErrorCode.INTERNAL_ERROR);
        }
    }

    private static ErrorCode mapError(RoomException ex) {
        return switch (ex.error()) {
            case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
            case NOT_A_ROOM_MEMBER -> ErrorCode.NOT_A_ROOM_MEMBER;
            default -> ErrorCode.INVALID_REQUEST;
        };
    }
}
