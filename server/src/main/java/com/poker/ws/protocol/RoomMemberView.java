package com.poker.ws.protocol;

import java.time.Instant;
import java.util.UUID;

public record RoomMemberView(
        UUID userId,
        String displayName,
        String avatarUrl,
        Instant joinedAt,
        Integer seat,
        boolean connected,
        Instant disconnectedSince,
        boolean admin) {
}
