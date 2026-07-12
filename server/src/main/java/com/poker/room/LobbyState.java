package com.poker.room;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable lobby projection captured under the registry lock. */
public record LobbyState(long revision, Instant serverNow, List<LobbyRoomState> rooms) {
    public LobbyState {
        Objects.requireNonNull(serverNow, "serverNow");
        Objects.requireNonNull(rooms, "rooms");
        if (revision < 0 || rooms.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid lobby projection");
        }
        rooms = List.copyOf(rooms);
    }
}
