package com.poker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.auth.AuthProvider;
import com.poker.auth.ModeratorPolicy;
import com.poker.auth.SessionUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class MeControllerTest {
    private static final UUID ID = UUID.fromString("91dfb7f8-01e6-4a28-8b64-650588c98f63");

    private static final String MODERATOR_EMAIL = "moderator@example.com";

    @Test
    void returnsOnlyTheAllowListedApplicationIdentity() {
        SessionUserPrincipal principal = new SessionUserPrincipal(ID, AuthProvider.DEV, "Alice", null);
        var response = new MeController(ModeratorPolicy.denyAll()).me(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new MeResponse(ID, "Alice", null, AuthProvider.DEV, false), response.getBody());
    }

    @Test
    void reportsModeratorStandingForAnAllowListedEmail() {
        var moderator = new SessionUserPrincipal(
                ID, AuthProvider.GOOGLE, "Alice", null, "Moderator@Example.com");
        var other = new SessionUserPrincipal(
                ID, AuthProvider.GOOGLE, "Bob", null, "bob@example.com");
        var controller = new MeController(ModeratorPolicy.of(List.of(MODERATOR_EMAIL), false));

        assertTrue(body(controller, moderator).moderator());
        assertFalse(body(controller, other).moderator());
    }

    private static MeResponse body(MeController controller, SessionUserPrincipal principal) {
        return controller.me(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities())).getBody();
    }

    @Test
    void rejectsUnexpectedAndUnauthenticatedPrincipalsWithoutCasting() {
        var controller = new MeController(ModeratorPolicy.denyAll());
        var unexpected = controller.me(
                UsernamePasswordAuthenticationToken.authenticated("provider-claim", null, java.util.List.of()));
        var unauthenticated = controller.me(
                UsernamePasswordAuthenticationToken.unauthenticated("anonymous", null));

        assertEquals(HttpStatus.UNAUTHORIZED, unexpected.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());
        assertNull(unexpected.getBody());
        assertNull(unauthenticated.getBody());
    }
}
