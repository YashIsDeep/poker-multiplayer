package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("oauth")
@TestPropertySource(properties = {
        "GOOGLE_CLIENT_ID=test-google",
        "GOOGLE_CLIENT_SECRET=test-google-secret",
        "DISCORD_CLIENT_ID=test-discord",
        "DISCORD_CLIENT_SECRET=test-discord-secret",
        "spring.datasource.url=jdbc:h2:mem:oauth_failure_session_test;DB_CLOSE_DELAY=-1",
        "poker.abuse.oauth-initiation-rate=1",
        "poker.abuse.oauth-initiation-burst=2"
})
class OAuthFailureSessionIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Test
    void malformedCallbacksDoNotCreateAnonymousSessionsOrAttributes() throws Exception {
        int sessionsBefore = sessionCount();
        int attributesBefore = sessionAttributeCount();

        mockMvc.perform(get("/login/oauth2/code/google"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?loginError"));
        mockMvc.perform(get("/login/oauth2/code/google")
                        .queryParam("code", "synthetic")
                        .queryParam("state", "synthetic"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?loginError"));
        mockMvc.perform(get("/login/oauth2/code/unknown")
                        .queryParam("error", "access_denied")
                        .queryParam("state", "synthetic"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?loginError"));

        assertThat(sessionCount()).isEqualTo(sessionsBefore);
        assertThat(sessionAttributeCount()).isEqualTo(attributesBefore);
    }

    @Test
    void authorizationInitiationIsBoundedPerPeer() throws Exception {
        assertThat(securityFilterChain.getFilters())
                .anyMatch(filter -> filter instanceof OAuthInitiationRateLimitFilter);
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound());
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound());
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isTooManyRequests());
    }

    private int sessionCount() {
        return jdbc.queryForObject("select count(*) from spring_session", Integer.class);
    }

    private int sessionAttributeCount() {
        return jdbc.queryForObject("select count(*) from spring_session_attributes", Integer.class);
    }
}
