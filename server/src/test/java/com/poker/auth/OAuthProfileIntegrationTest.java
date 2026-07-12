package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("oauth")
@TestPropertySource(properties = {
        "GOOGLE_CLIENT_ID=test-google",
        "GOOGLE_CLIENT_SECRET=test-google-secret",
        "DISCORD_CLIENT_ID=test-discord",
        "DISCORD_CLIENT_SECRET=test-discord-secret",
        "spring.datasource.url=jdbc:h2:mem:oauth_profile_test;DB_CLOSE_DELAY=-1"
})
class OAuthProfileIntegrationTest {

    @Autowired
    private ClientRegistrationRepository registrations;

    @Test
    void bindsGoogleOidcAndDiscordBasicAuthWithoutStartingRemoteAuthorization() {
        ClientRegistration google = registrations.findByRegistrationId("google");
        ClientRegistration discord = registrations.findByRegistrationId("discord");

        assertEquals("google", google.getRegistrationId());
        assertTrue(google.getScopes().contains("openid"));
        assertTrue(google.getScopes().contains("profile"));
        assertEquals("discord", discord.getRegistrationId());
        assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
                discord.getClientAuthenticationMethod());
        assertEquals("identify", discord.getScopes().iterator().next());
        assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", discord.getRedirectUri());
    }
}
