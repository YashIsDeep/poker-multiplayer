package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class SessionUserPrincipalTest {

    @Test
    void principalAndAuthenticationRoundTripWithoutCredentials() throws Exception {
        SessionUserPrincipal original = new SessionUserPrincipal(
                UUID.fromString("91dfb7f8-01e6-4a28-8b64-650588c98f63"),
                AuthProvider.DISCORD, "Alice", "https://example.com/a.png");
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                original, null, original.authorities());

        byte[] bytes = serialize(authentication);
        Authentication restored = (Authentication) new ObjectInputStream(
                new ByteArrayInputStream(bytes)).readObject();

        SessionUserPrincipal principal = assertInstanceOf(
                SessionUserPrincipal.class, restored.getPrincipal());
        assertEquals(original, principal);
        assertEquals(original.getName(), restored.getName());
        assertNull(restored.getCredentials());
        assertEquals(1, restored.getAuthorities().size());
        assertEquals("ROLE_USER", restored.getAuthorities().iterator().next().getAuthority());
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
