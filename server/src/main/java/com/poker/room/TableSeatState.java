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
        int rebuyHandsRemaining,
        int rebuyCount) {
    public TableSeatState(int seat, UUID userId, long initialBuyIn, long stack,
                          TableSeatStatus status, boolean sitOutAfterHand,
                          boolean standAfterHand, Long pendingRebuy,
                          int rebuyHandsRemaining) {
        this(seat, userId, initialBuyIn, stack, status, sitOutAfterHand, standAfterHand,
                pendingRebuy, rebuyHandsRemaining, 0);
    }

    /** Keeps the pre-count constructor usable for older room-state fixtures. */
    public TableSeatState(int seat, UUID userId, long initialBuyIn, long stack,
                          TableSeatStatus status, boolean sitOutAfterHand,
                          boolean standAfterHand, Long pendingRebuy,
                          int rebuyHandsRemaining, boolean rebought) {
        this(seat, userId, initialBuyIn, stack, status, sitOutAfterHand, standAfterHand,
                pendingRebuy, rebuyHandsRemaining, rebought ? 1 : 0);
    }
    public TableSeatState {
        if (seat < 0 || initialBuyIn <= 0 || stack < 0) {
            throw new IllegalArgumentException("invalid table seat");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        if (pendingRebuy != null && pendingRebuy <= 0) throw new IllegalArgumentException("invalid pending rebuy");
        if (rebuyHandsRemaining < 0 || rebuyCount < 0) throw new IllegalArgumentException("invalid rebuy state");
    }

    /** Compatibility projection for callers that only need to know whether a rebuy happened. */
    public boolean rebought() {
        return rebuyCount > 0;
    }
}
