package com.poker.room;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LobbyRoomState(
        RoomId id,
        JoinCode joinCode,
        String name,
        int maxSeats,
        int memberCount,
        int seatedCount,
        int connectedMemberCount,
        Instant createdAt) {
    public LobbyRoomState(RoomId id, JoinCode joinCode, String name, int maxSeats,
                          int memberCount, int seatedCount, Instant createdAt) {
        this(id, joinCode, name, maxSeats, memberCount, seatedCount, memberCount, createdAt);
    }

    public LobbyRoomState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(joinCode, "joinCode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        if (maxSeats < 2 || maxSeats > 9 || memberCount < 1
                || memberCount > maxSeats + PokerRoom.MAX_SPECTATORS
                || seatedCount < 0 || seatedCount > memberCount || seatedCount > maxSeats
                || connectedMemberCount < 0 || connectedMemberCount > memberCount) {
            throw new IllegalArgumentException("invalid lobby room projection");
        }
    }

    public UUID roomId() {
        return id.value();
    }
}
