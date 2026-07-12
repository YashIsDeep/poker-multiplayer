package com.poker.ws.protocol;

import java.time.Instant;
import java.util.UUID;

public record LobbyRoomView(
        UUID roomId,
        String joinCode,
        String name,
        int maxSeats,
        int memberCount,
        int seatedCount,
        Instant createdAt) {
}
