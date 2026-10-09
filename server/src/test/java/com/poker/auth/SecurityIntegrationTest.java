package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    private static final String DATABASE = "security_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    // The csrf() test postprocessor swaps the filter repository; isolate that mutation
    // from the no-session regression below.
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void publicEndpointsStayPublicButApiAndWebSocketBoundariesRequireSession() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/meta")).andExpect(status().isOk());
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/unknown")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ws")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/dev").param("u", "alice")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/dev/csrf")).andExpect(status().isNotFound());
        mockMvc.perform(post("/auth/dev").with(csrf()).param("u", "alice")).andExpect(status().isNotFound());
        mockMvc.perform(post("/logout")).andExpect(status().isForbidden());
    }

    @Test
    void rejectedAnonymousUnsafeRequestsDoNotCreatePersistentSessions() throws Exception {
        int sessionsBefore = sessionCount();
        int attributesBefore = sessionAttributeCount();

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/me").queryParam("audit", Integer.toString(i)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/me").queryParam("audit", Integer.toString(i)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/me").queryParam("audit", Integer.toString(i)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/me").queryParam("audit", Integer.toString(i)))
                    .andExpect(status().isForbidden());
        }

        assertThat(sessionCount()).isEqualTo(sessionsBefore);
        assertThat(sessionAttributeCount()).isEqualTo(attributesBefore);
    }

    private int sessionCount() {
        return jdbc.queryForObject("select count(*) from spring_session", Integer.class);
    }

    private int sessionAttributeCount() {
        return jdbc.queryForObject("select count(*) from spring_session_attributes", Integer.class);
    }
}
