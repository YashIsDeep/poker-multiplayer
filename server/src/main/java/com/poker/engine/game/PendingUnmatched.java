package com.poker.engine.game;

import java.util.Objects;

public record PendingUnmatched(PlayerId playerId, int seat, long amount) {
    public PendingUnmatched {
        Objects.requireNonNull(playerId, "playerId");
        if (seat < 0 || amount <= 0) {
            throw new IllegalArgumentException("invalid pending unmatched contribution");
        }
    }

    public PlayerId id() {
        return playerId;
    }
}
