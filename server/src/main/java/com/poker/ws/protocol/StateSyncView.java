package com.poker.ws.protocol;

public record StateSyncView(LobbySnapshot lobby, long membershipEpoch, RoomSnapshot room) {
}
