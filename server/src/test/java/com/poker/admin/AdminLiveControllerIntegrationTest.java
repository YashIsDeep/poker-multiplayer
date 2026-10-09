package com.poker.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poker.room.GameAction;
import com.poker.room.GameState;
import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.RoomState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "poker.admin.live.enabled=true",
        "poker.admin.live.token=test-admin-token",
        "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
class AdminLiveControllerIntegrationTest {
    private static final String DATABASE = "adminlive_" + UUID.randomUUID().toString().replace("-", "");
    private static final String TOKEN_HEADER = AdminLiveController.TOKEN_HEADER;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomManager roomManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void overviewAndRoomsReportLiveRegistryState() throws Exception {
        UUID aliceId = UUID.randomUUID();
        RoomChange created = roomManager.create(
                new MemberProfile(aliceId, "Alice", null), "Live Test Room", 6);
        UUID roomId = created.roomId();
        try {
            mockMvc.perform(get("/internal/admin/live/overview").header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roomCount").value(1))
                    .andExpect(jsonPath("$.memberCount").value(1));

            mockMvc.perform(get("/internal/admin/live/rooms").header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].roomId").value(roomId.toString()))
                    .andExpect(jsonPath("$[0].name").value("Live Test Room"))
                    .andExpect(jsonPath("$[0].tableStatus").value("WAITING"));

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId).header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members[0].displayName").value("Alice"))
                    .andExpect(jsonPath("$.game.tableStatus").value("WAITING"));

            mockMvc.perform(get("/internal/admin/live/connections").header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalSockets").value(0));
        } finally {
            roomManager.leave(aliceId, roomId);
        }
    }

    @Test
    void handDrilldownExposesCardsAndHistory() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        RoomChange created = roomManager.create(
                new MemberProfile(aliceId, "Alice", null), "Drilldown Room", 6);
        UUID roomId = created.roomId();
        try {
            roomManager.join(new MemberProfile(bobId, "Bob", null), created.room().joinCodeValue());
            roomManager.takeSeat(aliceId, roomId, 0);
            roomManager.takeSeat(bobId, roomId, 1);

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hand")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hands")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            GameState pending = roomManager.room(roomId).orElseThrow().game();
            Thread.sleep(2_100);
            roomManager.startHand(roomId, pending.lifecycleSeq(), pending.scheduleGeneration());

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hand")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.phase").value("PREFLOP"))
                    .andExpect(jsonPath("$.players.length()").value(2))
                    .andExpect(jsonPath("$.players[0].holeCards.length()").value(2))
                    .andExpect(jsonPath("$.players[1].holeCards.length()").value(2))
                    .andExpect(jsonPath("$.legalActions.callAmount").isNumber());

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hands")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].handId").value(1))
                    .andExpect(jsonPath("$[0].inProgress").value(true));

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hands/1")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.players.length()").value(2))
                    .andExpect(jsonPath("$.events[0].description").isNotEmpty());

            mockMvc.perform(get("/internal/admin/live/rooms/" + roomId + "/hands/99")
                            .header(TOKEN_HEADER, "test-admin-token"))
                    .andExpect(status().isNotFound());
        } finally {
            endAnyHandByFolding(roomId);
            roomManager.leave(bobId, roomId);
            roomManager.leave(aliceId, roomId);
        }
    }

    /** Folds and drains presentation until the room reaches a safe boundary so members can leave. */
    private void endAnyHandByFolding(UUID roomId) {
        for (int attempt = 0; attempt < 30 && !roomManager.room(roomId).orElseThrow().game().safeBoundary(); attempt++) {
            RoomState room = roomManager.room(roomId).orElseThrow();
            GameState game = room.game();
            var hand = game.presentedHand();
            if (hand != null && game.deadline() != null && hand.actingSeat().isPresent()) {
                UUID actor = room.seats().get(hand.actingSeat().getAsInt());
                roomManager.act(actor, roomId, game.actionSeq(), GameAction.FOLD, null);
            } else {
                roomManager.advancePresentation(roomId, game.handId(), game.scheduleGeneration());
            }
        }
    }

    @Test
    void unknownRoomIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/admin/live/rooms/" + UUID.randomUUID())
                        .header(TOKEN_HEADER, "test-admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingOrWrongTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/admin/live/overview"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/admin/live/overview").header(TOKEN_HEADER, "wrong"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonLoopbackCallersAreForbiddenEvenWithTheToken() throws Exception {
        mockMvc.perform(get("/internal/admin/live/overview")
                        .header(TOKEN_HEADER, "test-admin-token")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
            "Forwarded, for=127.0.0.1;host=localhost;proto=http, 203.0.113.10",
            "X-Forwarded-For, 127.0.0.1, 203.0.113.10",
            "Forwarded, for=127.0.0.1;host=localhost;proto=http, 127.0.0.1",
            "X-Forwarded-For, 127.0.0.1, 127.0.0.1",
            "X-Forwarded-Host, localhost, 127.0.0.1",
            "X-Forwarded-Proto, https, 127.0.0.1"
    })
    void forwardingCannotGrantAccessEvenWithTheToken(String header, String value, String peer) throws Exception {
        mockMvc.perform(get("/internal/admin/live/overview")
                        .header(TOKEN_HEADER, "test-admin-token")
                        .header(header, value)
                        .with(request -> {
                            request.setRemoteAddr(peer);
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicHostCannotAccessAdminThroughLoopbackProxy() throws Exception {
        mockMvc.perform(get("/internal/admin/live/overview")
                        .header(TOKEN_HEADER, "test-admin-token")
                        .with(request -> {
                            request.setServerName("poker.example.test");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }
}
