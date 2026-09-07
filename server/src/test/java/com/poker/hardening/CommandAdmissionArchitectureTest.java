package com.poker.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.ws.GameplayMessageController;
import com.poker.ws.ChatMessageController;
import com.poker.ws.RoomMessageController;
import com.poker.ws.protocol.CommandType;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;

class CommandAdmissionArchitectureTest {
    private static final Map<String, CommandType> COMMANDS = Map.ofEntries(
            Map.entry("/state.sync", CommandType.STATE_SYNC),
            Map.entry("/ranked.join", CommandType.RANKED_JOIN),
            Map.entry("/ranked.cancel", CommandType.RANKED_CANCEL),
            Map.entry("/room.create", CommandType.ROOM_CREATE),
            Map.entry("/room.join", CommandType.ROOM_JOIN),
            Map.entry("/room.leave", CommandType.ROOM_LEAVE),
            Map.entry("/room.sync", CommandType.ROOM_SYNC),
            Map.entry("/room.keep-alive", CommandType.ROOM_KEEP_ALIVE),
            Map.entry("/room.update-settings", CommandType.ROOM_UPDATE_SETTINGS),
            Map.entry("/room.take-seat", CommandType.ROOM_TAKE_SEAT),
            Map.entry("/room.stand", CommandType.ROOM_STAND),
            Map.entry("/room.chat", CommandType.CHAT_SEND),
            Map.entry("/game.act", CommandType.GAME_ACT),
            Map.entry("/game.sit-out", CommandType.GAME_SIT_OUT),
            Map.entry("/game.sit-in", CommandType.GAME_SIT_IN),
            Map.entry("/game.rebuy", CommandType.GAME_REBUY));

    @Test
    void everyMessageMappingHasExactlyOneMatchingAdmissionDeclaration() {
        assertController(RoomMessageController.class);
        assertController(com.poker.ws.RankedMessageController.class);
        assertController(ChatMessageController.class);
        assertController(GameplayMessageController.class);
    }

    private static void assertController(Class<?> controller) {
        for (Method method : controller.getDeclaredMethods()) {
            MessageMapping mapping = method.getAnnotation(MessageMapping.class);
            if (mapping == null) continue;
            assertThat(mapping.value()).as(method.toString()).hasSize(1);
            AdmittedCommand admitted = method.getAnnotation(AdmittedCommand.class);
            assertThat(admitted).as(method + " admission").isNotNull();
            assertThat(admitted.value()).as(method + " command")
                    .isEqualTo(COMMANDS.get(mapping.value()[0]));
            SendToUser ack = method.getAnnotation(SendToUser.class);
            assertThat(ack).as(method + " session-local ack").isNotNull();
            assertThat(ack.broadcast()).as(method + " ack broadcast").isFalse();
            assertThat(ack.destinations()).containsExactly("/queue/acks");
        }
    }
}
