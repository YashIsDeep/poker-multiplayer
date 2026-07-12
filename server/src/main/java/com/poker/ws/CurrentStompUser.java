package com.poker.ws;

import com.poker.auth.CurrentUserPrincipal;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/** Fail-closed extraction of the T4 application principal from a STOMP message. */
public record CurrentStompUser(UUID userId, String displayName, String avatarUrl) {
    public CurrentStompUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static CurrentStompUser require(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            throw new UnauthenticatedStompException();
        }
        if (!(authentication.getPrincipal() instanceof CurrentUserPrincipal current)) {
            throw new UnauthenticatedStompException();
        }
        return new CurrentStompUser(current.userId(), current.displayName(), current.avatarUrl());
    }

    public com.poker.room.MemberProfile profile() {
        return new com.poker.room.MemberProfile(userId, displayName, avatarUrl);
    }
}
