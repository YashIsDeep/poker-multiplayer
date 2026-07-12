package com.poker.engine.game;

import java.util.Objects;

public record PlayerAtSeat(PlayerId id, int seat, long stack) {
    public PlayerAtSeat {
        Objects.requireNonNull(id, "id");
        if (seat < 0) {
            throw new IllegalArgumentException("seat must not be negative");
        }
        if (stack <= 0) {
            throw new IllegalArgumentException("stack must be positive");
        }
    }
}
