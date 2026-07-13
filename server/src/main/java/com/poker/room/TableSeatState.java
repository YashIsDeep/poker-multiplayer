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
        Long pendingRebuy,
        int rebuyRoundsRemaining,
        boolean rebought) {
    public TableSeatState(int seat, UUID userId, long initialBuyIn, long stack,
                          TableSeatStatus status, boolean sitOutAfterHand,
                          boolean standAfterHand, Long pendingRebuy,
                          int rebuyRoundsRemaining) {
        this(seat, userId, initialBuyIn, stack, status, sitOutAfterHand, standAfterHand,
                pendingRebuy, rebuyRoundsRemaining, false);
    }
    public TableSeatState {
        if (seat < 0 || initialBuyIn <= 0 || stack < 0) {
            throw new IllegalArgumentException("invalid table seat");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        if (pendingRebuy != null && pendingRebuy <= 0) throw new IllegalArgumentException("invalid pending rebuy");
        if (rebuyRoundsRemaining < 0) throw new IllegalArgumentException("invalid rebuy cooldown");
    }
}
