package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poker.db.user.UserAccount;
import com.poker.db.user.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevLoginIntegrationTest {
    private static final String DATABASE = "dev_login_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void cleanUsers() {
        repository.deleteAllInBatch();
    }

    @Test
    void duplicateNamesCreateIndependentGuestIdentities() throws Exception {
        assertEquals("127.0.0.1", environment.getProperty("server.address"));

        Cookie firstCookie = login("Alice");
        UserAccount first = repository.findAll().getFirst();
        assertNotEquals("alice", first.getProviderSubject());
        UUID.fromString(first.getProviderSubject());
        mockMvc.perform(get("/api/me").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.provider").value("DEV"));

        Cookie secondCookie = login("alice");
        mockMvc.perform(get("/api/me").cookie(secondCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(first.getId().toString())))
                .andExpect(jsonPath("$.displayName").value("alice"));
        assertEquals(2, repository.count());
    }

    @Test
    void invalidNamesDoNotCreateUsersOrSessions() throws Exception {
        MvcResult missing = mockMvc.perform(post("/auth/dev").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertNull(missing.getResponse().getCookie("SESSION"));

        mockMvc.perform(post("/auth/dev").with(csrf()).param("u", "a".repeat(21)))
                .andExpect(status().isBadRequest());
        assertEquals(0, repository.count());
    }

    @Test
    void loginRejectsMissingInvalidAndCrossSessionCsrfTokens() throws Exception {
        mockMvc.perform(get("/auth/dev").param("u", "Alice"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/dev").param("u", "Alice"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/auth/dev").with(csrf().useInvalidToken()).param("u", "Alice"))
                .andExpect(status().isForbidden());
        MvcResult bootstrap = mockMvc.perform(get("/auth/dev/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        var csrf = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(bootstrap.getResponse().getContentAsString());
        String headerName = csrf.path("headerName").asText();
        String token = csrf.path("token").asText();
        mockMvc.perform(post("/auth/dev").param("u", "Alice").header(headerName, token))
                .andExpect(status().isForbidden());
        assertEquals(0, repository.count());
        mockMvc.perform(post("/auth/dev").param("u", "Alice").header(headerName, token)
                        .cookie(bootstrap.getResponse().getCookie("SESSION")))
                .andExpect(status().isFound());
        assertEquals(1, repository.count());
    }

    @Test
    void guestServerRejectsNonLoopbackPeersPublicHostsAndForwardingHeaders() throws Exception {
        mockMvc.perform(post("/auth/dev").with(csrf()).param("u", "Alice").with(request -> {
            request.setRemoteAddr("203.0.113.10");
            return request;
        })).andExpect(status().isForbidden());
        mockMvc.perform(get("/auth/dev/csrf").header("Host", "table.example.com").with(request -> {
            request.setServerName("table.example.com");
            return request;
        })).andExpect(status().isForbidden());
        for (String header : java.util.List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto")) {
            mockMvc.perform(post("/auth/dev").with(csrf()).param("u", "Alice").header(header, "127.0.0.1"))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, repository.count());
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesTheSession() throws Exception {
        Cookie session = login("Alice");

        mockMvc.perform(post("/logout").cookie(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    private Cookie login(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/dev").with(csrf()).param("u", name))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertNotNull(cookie);
        return cookie;
    }
}
