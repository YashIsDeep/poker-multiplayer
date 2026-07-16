package com.poker.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "poker.admin.live.enabled=true",
        "poker.admin.live.token=test-admin-token"
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
}
