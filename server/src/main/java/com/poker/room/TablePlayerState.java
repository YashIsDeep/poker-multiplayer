package com.poker.room;

/** Persistent table balance for a room member, including while they are off the felt. */
public record TablePlayerState(
        long stack,
        int rebuyHandsRemaining,
        int rebuyCount,
        long bustedOrder) {
    public TablePlayerState {
        if (stack < 0 || rebuyHandsRemaining < 0 || rebuyCount < 0 || bustedOrder < 0
                || (stack > 0 && bustedOrder != 0)) {
            throw new IllegalArgumentException("invalid table player state");
        }
    }

    /** Compatibility projection for callers that only need to know whether a rebuy happened. */
    public boolean rebought() {
        return rebuyCount > 0;
    }

    /** Keeps the pre-count constructor usable for older room-state fixtures. */
    public TablePlayerState(long stack, int rebuyHandsRemaining, boolean rebought, long bustedOrder) {
        this(stack, rebuyHandsRemaining, rebought ? 1 : 0, bustedOrder);
    }
}
