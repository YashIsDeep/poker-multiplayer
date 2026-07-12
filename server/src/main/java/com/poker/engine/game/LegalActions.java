package com.poker.engine.game;

import java.util.Objects;

public record LegalActions(
        PlayerId playerId,
        long toCall,
        boolean canFold,
        boolean canCheck,
        boolean canCall,
        long callAmount,
        boolean canRaise,
        long minimumRaiseTo,
        long maximumRaiseTo
) {
    public LegalActions {
        Objects.requireNonNull(playerId, "playerId");
        if (toCall < 0 || callAmount < 0 || minimumRaiseTo < 0 || maximumRaiseTo < 0) {
            throw new IllegalArgumentException("legal-action amounts cannot be negative");
        }
        if (!canRaise && (minimumRaiseTo != 0 || maximumRaiseTo != 0)) {
            throw new IllegalArgumentException("raise bounds must be zero when raising is unavailable");
        }
        if (canRaise && (minimumRaiseTo <= 0 || maximumRaiseTo < minimumRaiseTo)) {
            throw new IllegalArgumentException("invalid raise bounds");
        }
    }
}
