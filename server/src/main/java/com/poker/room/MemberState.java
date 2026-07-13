package com.poker.room;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, transport-neutral member projection captured under a room lock. */
public record MemberState(
        UUID userId,
        String displayName,
        String avatarUrl,
        Instant joinedAt,
        Integer seat,
        boolean connected,
        Instant disconnectedSince) {
    public MemberState(UUID userId, String displayName, String avatarUrl, Instant joinedAt, Integer seat) {
        this(userId, displayName, avatarUrl, joinedAt, seat, true, null);
    }

    public MemberState {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(joinedAt, "joinedAt");
        if (displayName.isBlank() || (seat != null && seat < 0)
                || (connected && disconnectedSince != null)
                || (!connected && disconnectedSince == null)) {
            throw new IllegalArgumentException("invalid member projection");
        }
    }

    public boolean seated() {
        return seat != null;
    }
}
