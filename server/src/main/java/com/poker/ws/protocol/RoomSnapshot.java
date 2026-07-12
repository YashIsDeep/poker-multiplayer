package com.poker.ws.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoomSnapshot(
        UUID roomId,
        String joinCode,
        String name,
        int maxSeats,
        long membershipEpoch,
        long revision,
        Instant serverNow,
        List<RoomMemberView> members,
        List<SeatView> seats,
        RoomConfigView config,
        ViewerRoomState viewer,
        GameView game) {
    public RoomSnapshot {
        members = List.copyOf(members);
        seats = List.copyOf(seats);
    }
}
