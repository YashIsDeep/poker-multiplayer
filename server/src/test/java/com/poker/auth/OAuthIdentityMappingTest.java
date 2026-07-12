package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poker.db.user.UserAccount;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class OAuthIdentityMappingTest {
    private static final UUID USER_ID = UUID.fromString("91dfb7f8-01e6-4a28-8b64-650588c98f63");

    @Test
    void discordUsesOpaqueStringIdGlobalNameAndSafeAvatarConstruction() {
        UserProvisioningService provisioning = mock(UserProvisioningService.class);
        UserAccount account = account(AuthProvider.DISCORD, "Captain");
        when(provisioning.provision(any())).thenReturn(account);
        OAuth2User providerUser = mock(OAuth2User.class);
        when(providerUser.getAttributes()).thenReturn(Map.of(
                "id", "12345678901234567890",
                "global_name", "Captain",
                "username", "fallback",
                "avatar", "a_hash_123"));

        DiscordOAuth2UserService service = new DiscordOAuth2UserService(
                request -> providerUser, provisioning);
        OAuth2User result = service.loadUser(new OAuth2UserRequest(
                discordRegistration(), accessToken()));

        assertInstanceOf(ProvisionedOAuth2User.class, result);
        ArgumentCaptor<ExternalIdentity> identity = ArgumentCaptor.forClass(ExternalIdentity.class);
        verify(provisioning).provision(identity.capture());
        assertEquals(AuthProvider.DISCORD, identity.getValue().provider());
        assertEquals("12345678901234567890", identity.getValue().providerSubject());
        assertEquals("Captain", identity.getValue().displayName());
        assertEquals("https://cdn.discordapp.com/avatars/12345678901234567890/a_hash_123.png",
                identity.getValue().avatarUrl());
        assertEquals(USER_ID.toString(), result.getName());
    }

    @Test
    void discordRejectsMalformedIdsBeforeProvisioning() {
        UserProvisioningService provisioning = mock(UserProvisioningService.class);
        OAuth2User providerUser = mock(OAuth2User.class);
        when(providerUser.getAttributes()).thenReturn(Map.of("id", "not-a-snowflake"));
        DiscordOAuth2UserService service = new DiscordOAuth2UserService(
                request -> providerUser, provisioning);

        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(new OAuth2UserRequest(
                discordRegistration(), accessToken())));
        verify(provisioning, never()).provision(any());
    }

    @Test
    void googleUsesOidcSubjectRatherThanEmailAndDiscardsInvalidPicture() {
        UserProvisioningService provisioning = mock(UserProvisioningService.class);
        UserAccount account = account(AuthProvider.GOOGLE, "Alice");
        when(provisioning.provision(any())).thenReturn(account);
        OidcUser providerUser = mock(OidcUser.class);
        when(providerUser.getClaims()).thenReturn(Map.of(
                "sub", "google-subject",
                "email", "alice@example.com",
                "name", "Alice",
                "picture", "http://unsafe.example/avatar.png"));

        GoogleOidcUserService service = new GoogleOidcUserService(
                request -> providerUser, provisioning);
        OidcIdToken idToken = new OidcIdToken(
                "id-token",
                Instant.parse("2026-07-12T08:00:00Z"),
                Instant.parse("2026-07-12T09:00:00Z"),
                Map.of("sub", "google-subject"));
        OidcUser result = service.loadUser(new OidcUserRequest(googleRegistration(), accessToken(), idToken));

        assertInstanceOf(ProvisionedOidcUser.class, result);
        ArgumentCaptor<ExternalIdentity> identity = ArgumentCaptor.forClass(ExternalIdentity.class);
        verify(provisioning).provision(identity.capture());
        assertEquals(AuthProvider.GOOGLE, identity.getValue().provider());
        assertEquals("google-subject", identity.getValue().providerSubject());
        assertEquals("Alice", identity.getValue().displayName());
        org.junit.jupiter.api.Assertions.assertNull(identity.getValue().avatarUrl());
    }

    private static UserAccount account(AuthProvider provider, String displayName) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(USER_ID);
        when(account.getProvider()).thenReturn(provider);
        when(account.getDisplayName()).thenReturn(displayName);
        when(account.getAvatarUrl()).thenReturn(null);
        return account;
    }

    private static OAuth2AccessToken accessToken() {
        return new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "provider-access-token",
                Instant.parse("2026-07-12T08:00:00Z"),
                Instant.parse("2026-07-12T09:00:00Z"));
    }

    private static ClientRegistration discordRegistration() {
        return registration("discord")
                .userInfoUri("https://discord.example/user")
                .userNameAttributeName("id")
                .build();
    }

    private static ClientRegistration googleRegistration() {
        return registration("google").scope("openid", "profile").build();
    }

    private static ClientRegistration.Builder registration(String registrationId) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId("client")
                .clientSecret("secret")
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}");
    }
}
