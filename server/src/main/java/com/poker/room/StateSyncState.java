package com.poker.room;

import java.util.Objects;

/** Authoritative reconnect projection; room may be null when the viewer is in the lobby. */
public record StateSyncState(LobbyState lobby, long membershipEpoch, RoomState room) {
    public StateSyncState {
        Objects.requireNonNull(lobby, "lobby");
        if (membershipEpoch < 0) {
            throw new IllegalArgumentException("membershipEpoch cannot be negative");
        }
    }
}
