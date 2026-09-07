package com.poker.ws.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoomSnapshot(
        UUID roomId,
        String joinCode,
        String name,
        int maxSeats,
        boolean privateTable,
        boolean canEditSettings,
        long membershipEpoch,
        long revision,
        Instant serverNow,
        Instant idleExpiresAt,
        List<RoomMemberView> members,
        List<SeatView> seats,
        RoomConfigView config,
        ViewerRoomState viewer,
        GameView game,
        String mode,
        String rankedStatus) {
    public RoomSnapshot {
        members = List.copyOf(members);
        seats = List.copyOf(seats);
    }
}
