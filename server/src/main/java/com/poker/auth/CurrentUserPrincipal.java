package com.poker.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public interface CurrentUserPrincipal extends Principal, Serializable {
    UUID userId();

    AuthProvider provider();

    String displayName();

    String avatarUrl();

    @Override
    default String getName() {
        return userId().toString();
    }
}
