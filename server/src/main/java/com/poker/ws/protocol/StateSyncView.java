package com.poker.ws.protocol;

public record StateSyncView(LobbySnapshot lobby, long membershipEpoch, RoomSnapshot room, com.poker.ranked.RankedQueue.State rankedQueue) {
    public StateSyncView(LobbySnapshot lobby, long membershipEpoch, RoomSnapshot room) {
        this(lobby, membershipEpoch, room, null);
    }
}
