package com.poker.ws.protocol;

import java.util.UUID;

public record ViewerRoomState(
        UUID userId,
        RoomMemberView member,
        boolean seated,
        Integer seat,
        boolean canTakeSeat,
        boolean canStand) {
}
