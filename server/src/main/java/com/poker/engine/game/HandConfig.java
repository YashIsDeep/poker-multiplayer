package com.poker.engine.game;

public record HandConfig(long smallBlind, long bigBlind, int maxSeats) {
    public HandConfig {
        if (smallBlind <= 0) {
            throw new IllegalArgumentException("smallBlind must be positive");
        }
        if (bigBlind < smallBlind) {
            throw new IllegalArgumentException("bigBlind must be at least smallBlind");
        }
        if (maxSeats < 2 || maxSeats > 9) {
            throw new IllegalArgumentException("maxSeats must be between 2 and 9");
        }
    }
}
