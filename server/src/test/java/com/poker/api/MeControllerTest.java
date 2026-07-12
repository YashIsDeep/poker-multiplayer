package com.poker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.poker.auth.AuthProvider;
import com.poker.auth.SessionUserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class MeControllerTest {
    private static final UUID ID = UUID.fromString("91dfb7f8-01e6-4a28-8b64-650588c98f63");

    @Test
    void returnsOnlyTheAllowListedApplicationIdentity() {
        SessionUserPrincipal principal = new SessionUserPrincipal(ID, AuthProvider.DEV, "Alice", null);
        var response = new MeController().me(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new MeResponse(ID, "Alice", null, AuthProvider.DEV), response.getBody());
    }

    @Test
    void rejectsUnexpectedAndUnauthenticatedPrincipalsWithoutCasting() {
        var controller = new MeController();
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
