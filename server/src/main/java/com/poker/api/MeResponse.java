package com.poker.api;

import com.poker.auth.AuthProvider;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String displayName,
        String avatarUrl,
        AuthProvider provider,
        boolean moderator) {
}
