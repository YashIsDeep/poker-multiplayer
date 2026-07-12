package com.poker.engine.game;

import java.util.Objects;

public record WinnerAward(PlayerId playerId, int seat, long amount) {
    public WinnerAward {
        Objects.requireNonNull(playerId, "playerId");
        if (seat < 0 || amount <= 0) {
            throw new IllegalArgumentException("invalid winner award");
        }
    }

    public PlayerId id() {
        return playerId;
    }
}
