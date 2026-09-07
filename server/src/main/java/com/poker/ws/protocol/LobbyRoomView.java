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
        int connectedMemberCount,
        Instant createdAt,
        String mode,
        String rankedStatus) {
    public LobbyRoomView(
        UUID roomId,
        String joinCode,
        String name,
        int maxSeats,
        int memberCount,
        int seatedCount,
        int connectedMemberCount,
        Instant createdAt) {
        this(roomId, joinCode, name, maxSeats, memberCount, seatedCount, connectedMemberCount, createdAt, "CASUAL", null);
    }
}
