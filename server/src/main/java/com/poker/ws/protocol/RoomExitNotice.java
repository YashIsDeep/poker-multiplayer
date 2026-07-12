package com.poker.ws.protocol;

import java.time.Instant;
import java.util.UUID;

public record RoomExitNotice(UUID roomId, ExitReason reason, long membershipEpoch, Instant serverNow) {
    public enum ExitReason {
        LEFT
    }
}
