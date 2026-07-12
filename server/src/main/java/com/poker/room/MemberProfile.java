package com.poker.room;

import java.util.Objects;
import java.util.UUID;

/** Safe identity/profile fields supplied by the authenticated application principal. */
public record MemberProfile(UUID userId, String displayName, String avatarUrl) {
    public MemberProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
