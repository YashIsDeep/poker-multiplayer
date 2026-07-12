package com.poker.engine.game;

import java.util.List;
import java.util.Objects;

public record PotAward(int potIndex, long potAmount, List<WinnerAward> winnerAwards) {
    public PotAward {
        if (potIndex < 0 || potAmount <= 0) {
            throw new IllegalArgumentException("invalid pot award");
        }
        Objects.requireNonNull(winnerAwards, "winnerAwards");
        if (winnerAwards.isEmpty() || winnerAwards.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("pot awards require winners");
        }
        winnerAwards = List.copyOf(winnerAwards);
    }

    public long amount() {
        return potAmount;
    }
}
