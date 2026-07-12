package com.poker.room;

import java.util.Objects;
import java.util.UUID;

public record TableSeatState(
        int seat,
        UUID userId,
        long initialBuyIn,
        long stack,
        TableSeatStatus status,
        boolean sitOutAfterHand,
        boolean standAfterHand,
        Long pendingRebuy) {
    public TableSeatState {
        if (seat < 0 || initialBuyIn <= 0 || stack < 0) {
            throw new IllegalArgumentException("invalid table seat");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        if (pendingRebuy != null && pendingRebuy <= 0) throw new IllegalArgumentException("invalid pending rebuy");
    }
}
