package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poker.db.user.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SessionPersistenceIntegrationTest {
    private static final String DATABASE = "session_round_trip_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository repository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void jdbcSessionRestoresTheTokenFreePrincipalOnARequestWithOnlyTheCookie() throws Exception {
        MvcResult login = mockMvc.perform(post("/auth/dev").with(csrf()).param("u", "RoundTrip"))
                .andExpect(status().isFound())
                .andReturn();
        Cookie session = login.getResponse().getCookie("SESSION");
        assertNotNull(session);
        String sessionId = new String(
                Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);

        UUID userId = repository.findAll().getFirst().getId();
        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.displayName").value("RoundTrip"));

        String primaryId = jdbc.queryForObject(
                "select primary_id from spring_session where session_id = ?", String.class, sessionId);
        assertNotNull(primaryId);
        int securityContextAttributes = jdbc.queryForObject(
                "select count(*) from spring_session_attributes "
                        + "where session_primary_id = ? and attribute_name = 'SPRING_SECURITY_CONTEXT'",
                Integer.class, primaryId);
        assertEquals(1, securityContextAttributes,
                () -> jdbc.queryForList("select session_primary_id, attribute_name from spring_session_attributes")
                        .toString());
        byte[] serializedContext = jdbc.queryForObject(
                "select attribute_bytes from spring_session_attributes "
                        + "where session_primary_id = ? and attribute_name = 'SPRING_SECURITY_CONTEXT'",
                byte[].class, primaryId);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serializedContext))) {
            SecurityContext storedContext = (SecurityContext) input.readObject();
            Authentication storedAuthentication = storedContext.getAuthentication();
            assertEquals(UsernamePasswordAuthenticationToken.class, storedAuthentication.getClass());
            assertEquals(SessionUserPrincipal.class, storedAuthentication.getPrincipal().getClass());
            assertEquals(null, storedAuthentication.getCredentials());
            assertEquals(1, storedAuthentication.getAuthorities().size());
            assertEquals("ROLE_USER", storedAuthentication.getAuthorities().iterator().next().getAuthority());
        }
        assertEquals(1, repository.count());
    }
}
