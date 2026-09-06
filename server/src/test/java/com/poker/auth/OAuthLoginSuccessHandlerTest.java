package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class OAuthLoginSuccessHandlerTest {
    private static final String CONTEXT_KEY =
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final UUID USER_ID = UUID.fromString("91dfb7f8-01e6-4a28-8b64-650588c98f63");

    private final HttpSessionSecurityContextRepository repository =
            new HttpSessionSecurityContextRepository();
    private final OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(
            repository, (authentication, request, response) -> { });

    @Test
    void replacesGoogleProtocolAuthenticationWithTheSessionPrincipal() throws Exception {
        SessionUserPrincipal sessionPrincipal = new SessionUserPrincipal(
                USER_ID, AuthProvider.GOOGLE, "Alice", null);
        ProvisionedOidcUser wrapped = new ProvisionedOidcUser(mock(OidcUser.class), sessionPrincipal);
        assertReplacedAndRedirectedToLobby(
                new OAuth2AuthenticationToken(wrapped, wrapped.getAuthorities(), "google"),
                sessionPrincipal);
    }

    @Test
    void replacesDiscordProtocolAuthenticationWithTheSessionPrincipal() throws Exception {
        SessionUserPrincipal sessionPrincipal = new SessionUserPrincipal(
                USER_ID, AuthProvider.DISCORD, "Captain", null);
        ProvisionedOAuth2User wrapped = new ProvisionedOAuth2User(
                mock(OAuth2User.class), sessionPrincipal);
        assertReplacedAndRedirectedToLobby(
                new OAuth2AuthenticationToken(wrapped, wrapped.getAuthorities(), "discord"),
                sessionPrincipal);
    }

    @Test
    void unexpectedPrincipalFailsClosedAndScrubsTheSavedProtocolContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken protocolAuthentication = new OAuth2AuthenticationToken(
                mock(OAuth2User.class), java.util.List.of(), "google");
        // Simulate the OAuth filter having already persisted the protocol authentication.
        request.getSession(true).setAttribute(
                CONTEXT_KEY, new SecurityContextImpl(protocolAuthentication));

        handler.onAuthenticationSuccess(request, response, protocolAuthentication);

        assertEquals("/login?loginError", response.getRedirectedUrl());
        assertNull(request.getSession(false).getAttribute(CONTEXT_KEY));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void assertReplacedAndRedirectedToLobby(
            OAuth2AuthenticationToken protocolAuthentication,
            SessionUserPrincipal sessionPrincipal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.getSession(true).setAttribute(
                CONTEXT_KEY, new SecurityContextImpl(protocolAuthentication));

        handler.onAuthenticationSuccess(request, response, protocolAuthentication);

        assertEquals("/", response.getRedirectedUrl());
        SecurityContext stored = (SecurityContext) request.getSession(false)
                .getAttribute(CONTEXT_KEY);
        Authentication storedAuthentication = stored.getAuthentication();
        assertEquals(UsernamePasswordAuthenticationToken.class, storedAuthentication.getClass());
        assertSame(sessionPrincipal, storedAuthentication.getPrincipal());
        assertNull(storedAuthentication.getCredentials());
        assertEquals(1, storedAuthentication.getAuthorities().size());
        assertEquals("ROLE_USER",
                storedAuthentication.getAuthorities().iterator().next().getAuthority());
    }
}
