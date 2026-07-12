package com.poker.engine.game;

import com.poker.engine.cards.Card;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record PlayerSnapshot(
        PlayerId id,
        int seat,
        long stack,
        long streetCommitted,
        long handCommitted,
        boolean folded,
        boolean allIn,
        List<Card> holeCards
) {
    public PlayerSnapshot {
        Objects.requireNonNull(id, "id");
        if (seat < 0 || stack < 0 || streetCommitted < 0 || handCommitted < 0) {
            throw new IllegalArgumentException("player snapshot values cannot be negative");
        }
        if (streetCommitted > handCommitted) {
            throw new IllegalArgumentException("streetCommitted cannot exceed handCommitted");
        }
        Objects.requireNonNull(holeCards, "holeCards");
        if (holeCards.stream().anyMatch(Objects::isNull) || holeCards.size() > 2) {
            throw new IllegalArgumentException("holeCards must contain zero to two non-null cards");
        }
        if (new HashSet<>(holeCards).size() != holeCards.size()) {
            throw new IllegalArgumentException("holeCards cannot contain duplicates");
        }
        holeCards = List.copyOf(holeCards);
    }

    public PlayerId playerId() {
        return id;
    }
}
