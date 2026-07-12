package com.poker.room;

import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandSnapshot;
import com.poker.engine.game.LegalActions;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable trusted projection captured under the room lock; never serialized directly. */
public record GameState(
        TableStatus tableStatus,
        long handId,
        long actionSeq,
        Instant deadline,
        HandSnapshot presentedHand,
        LegalActions legalActions,
        List<TableSeatState> seats,
        Map<Integer, HandEvent.PlayerActed> lastActions,
        long lifecycleSeq,
        long scheduleGeneration,
        Instant pausedSince,
        Instant nextHandAt,
        boolean safeBoundary) {
    public GameState {
        seats = List.copyOf(seats);
        lastActions = Map.copyOf(lastActions);
    }
}
