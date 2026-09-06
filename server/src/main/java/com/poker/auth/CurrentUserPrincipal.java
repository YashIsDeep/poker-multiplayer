package com.poker.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public interface CurrentUserPrincipal extends Principal, Serializable {
    UUID userId();

    AuthProvider provider();

    String displayName();

    String avatarUrl();

    /** Verified provider email, or null when the provider supplied none. */
    String email();

    @Override
    default String getName() {
        return userId().toString();
    }
}
