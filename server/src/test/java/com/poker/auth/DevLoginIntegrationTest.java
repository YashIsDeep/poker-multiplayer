package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void devLoginCreatesAStableSessionIdentityAndIsLoopbackOnly() throws Exception {
        assertEquals("127.0.0.1", environment.getProperty("server.address"));

        Cookie firstCookie = login("Alice");
        UserAccount first = repository.findByProviderAndProviderSubject(AuthProvider.DEV, "alice")
                .orElseThrow();
        mockMvc.perform(get("/api/me").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.provider").value("DEV"));

        Cookie secondCookie = login("alice");
        UserAccount second = repository.findByProviderAndProviderSubject(AuthProvider.DEV, "alice")
                .orElseThrow();
        assertEquals(first.getId(), second.getId());
        assertNotNull(secondCookie);
        assertEquals(1, repository.count());
    }

    @Test
    void invalidNamesDoNotCreateUsersOrSessions() throws Exception {
        MvcResult missing = mockMvc.perform(get("/auth/dev"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertNull(missing.getResponse().getCookie("SESSION"));

        mockMvc.perform(get("/auth/dev").param("u", "a".repeat(21)))
                .andExpect(status().isBadRequest());
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
        MvcResult result = mockMvc.perform(get("/auth/dev").param("u", name))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/lobby"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertNotNull(cookie);
        return cookie;
    }
}
