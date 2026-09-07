package com.poker.ws;

import com.poker.hardening.AdmittedCommand;
import com.poker.ranked.RankedQueue;
import com.poker.room.RoomException;
import com.poker.ws.protocol.*;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

@Controller
@Validated
public class RankedMessageController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RankedMessageController.class);
    private final RankedQueue queue;
    public RankedMessageController(RankedQueue queue) { this.queue = queue; }

    @MessageMapping("/ranked.join")
    @AdmittedCommand(CommandType.RANKED_JOIN)
    @SendToUser(destinations="/queue/acks", broadcast=false)
    public CommandAck join(@Valid @Payload CommandEnvelope<SyncStateRequest> request, Principal principal) {
        return execute(request, principal, true);
    }

    @MessageMapping("/ranked.cancel")
    @AdmittedCommand(value=CommandType.RANKED_CANCEL, control=true)
    @SendToUser(destinations="/queue/acks", broadcast=false)
    public CommandAck cancel(@Valid @Payload CommandEnvelope<SyncStateRequest> request, Principal principal) {
        return execute(request, principal, false);
    }

    private CommandAck execute(CommandEnvelope<SyncStateRequest> request, Principal principal, boolean join) {
        var command = join ? CommandType.RANKED_JOIN : CommandType.RANKED_CANCEL;
        try {
            var user = CurrentStompUser.require(principal);
            if (join) queue.join(user.profile()); else queue.cancel(user.userId());
            return CommandAck.success(request.requestId(), command, queue.state(user.userId()).roomId(), 0, 0);
        } catch (UnauthenticatedStompException ex) {
            return CommandAck.failure(request.requestId(), command, ErrorCode.UNAUTHENTICATED);
        } catch (RankedQueue.Unavailable ex) {
            return CommandAck.failure(request.requestId(), command, ErrorCode.RANKED_UNAVAILABLE);
        } catch (RoomException ex) {
            return CommandAck.failure(request.requestId(), command, ErrorCode.valueOf(ex.error().name()));
        } catch (RuntimeException ex) {
            log.error("Ranked command failed command={}", command, ex);
            return CommandAck.failure(request.requestId(), command, ErrorCode.INTERNAL_ERROR);
        }
    }
}
