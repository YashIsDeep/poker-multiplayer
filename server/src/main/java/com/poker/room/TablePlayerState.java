package com.poker.room;

/** Persistent table balance for a room member, including while they are off the felt. */
public record TablePlayerState(
        long stack,
        int rebuyHandsRemaining,
        boolean rebought,
        long bustedOrder) {
    public TablePlayerState {
        if (stack < 0 || rebuyHandsRemaining < 0 || bustedOrder < 0
                || (stack > 0 && bustedOrder != 0)) {
            throw new IllegalArgumentException("invalid table player state");
        }
    }
}
