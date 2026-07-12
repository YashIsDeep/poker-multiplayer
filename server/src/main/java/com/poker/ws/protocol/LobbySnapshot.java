package com.poker.ws.protocol;

import java.time.Instant;
import java.util.List;

public record LobbySnapshot(long revision, Instant serverNow, List<LobbyRoomView> rooms) {
    public LobbySnapshot {
        rooms = List.copyOf(rooms);
    }
}
