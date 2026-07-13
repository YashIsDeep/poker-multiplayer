package com.poker.room;

import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandSnapshot;
import com.poker.engine.game.LegalActions;
import java.time.Instant;
import java.util.LinkedHashMap;
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
        Map<Integer, List<HandEvent.PlayerActed>> roundActions,
        long lifecycleSeq,
        long scheduleGeneration,
        Instant pausedSince,
        Instant nextHandAt,
        boolean safeBoundary) {
    public GameState {
        seats = List.copyOf(seats);
        Map<Integer, List<HandEvent.PlayerActed>> copiedActions = new LinkedHashMap<>();
        roundActions.forEach((seat, actions) -> copiedActions.put(seat, List.copyOf(actions)));
        roundActions = Map.copyOf(copiedActions);
    }
}
