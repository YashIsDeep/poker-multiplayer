package com.poker.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RankedControllerIntegrationTest {
    static final String DB = "ranked_api_" + UUID.randomUUID();
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DB + ";DB_CLOSE_DELAY=-1");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void anonymousReadsExposeOnlyPublicStatsAndSupportPagination() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, provider, provider_subject, display_name, avatar_url,"
                        + " created_at, last_login_at) VALUES"
                        + " (?, 'GOOGLE', 'private-subject', 'Public Name', NULL, ?, ?)", id, OffsetDateTime.now(), OffsetDateTime.now());
        jdbc.update("INSERT INTO ranked_player_total VALUES (?, -1250, 1000)", id);
        mvc.perform(get("/api/players/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Public Name"))
                .andExpect(jsonPath("$.bbPer100").value(-12.5))
                .andExpect(jsonPath("$.netBB").value(-125))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.providerSubject").doesNotExist())
                .andExpect(jsonPath("$.holeCards").doesNotExist());
        mvc.perform(get("/api/leaderboard?page=0&pageSize=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].userId").value(id.toString()))
                .andExpect(jsonPath("$.players[0].rank").value(1));
        mvc.perform(get("/api/leaderboard?page=1&pageSize=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(0));
    }

    @Test void validatesPaginationAndIdsAndDoesNotExposeWrites() throws Exception {
        for (String query : new String[]{"page=-1", "pageSize=0", "pageSize=101"})
            mvc.perform(get("/api/leaderboard?" + query)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/players/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/players/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/leaderboard")).andExpect(status().isForbidden());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/leaderboard")).andExpect(status().isOk());
        mvc.perform(get("/players/" + UUID.randomUUID())).andExpect(status().isOk());
    }
}
