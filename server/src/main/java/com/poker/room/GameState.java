package com.poker.room;

import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandSnapshot;
import com.poker.engine.game.LegalActions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable trusted projection captured under the room lock; never serialized directly. */
public record GameState(
        TableStatus tableStatus,
        long handId,
        long actionSeq,
        Instant deadline,
        HandSnapshot presentedHand,
        LegalActions legalActions,
        List<TableSeatState> seats,
        Map<UUID, TablePlayerState> players,
        Map<Integer, List<HandEvent.PlayerActed>> roundActions,
        long lifecycleSeq,
        long scheduleGeneration,
        Instant pausedSince,
        Instant nextHandAt,
        UUID rebuyOfferUserId,
        Instant rebuyOfferExpiresAt,
        List<HandHistory> handHistories,
        boolean safeBoundary,
        boolean ranked,
        boolean consolidationHeld) {
    public String rankedStatus() {
        if (!ranked) return null;
        if (tableStatus == TableStatus.SETTLING) return "SETTLING";
        if (consolidationHeld) return "CONSOLIDATING";
        return tableStatus == TableStatus.WAITING && presentedHand == null ? "WAITING_FOR_PLAYERS" : "PLAYING";
    }
    public GameState(
        TableStatus tableStatus,
        long handId,
        long actionSeq,
        Instant deadline,
        HandSnapshot presentedHand,
        LegalActions legalActions,
        List<TableSeatState> seats,
        Map<UUID, TablePlayerState> players,
        Map<Integer, List<HandEvent.PlayerActed>> roundActions,
        long lifecycleSeq,
        long scheduleGeneration,
        Instant pausedSince,
        Instant nextHandAt,
        UUID rebuyOfferUserId,
        Instant rebuyOfferExpiresAt,
        List<HandHistory> handHistories,
        boolean safeBoundary) {
        this(tableStatus, handId, actionSeq, deadline, presentedHand, legalActions, seats, players,
                roundActions, lifecycleSeq, scheduleGeneration, pausedSince, nextHandAt, rebuyOfferUserId,
                rebuyOfferExpiresAt, handHistories, safeBoundary, false, false);
    }
    public GameState {
        seats = List.copyOf(seats);
        players = Map.copyOf(players);
        Map<Integer, List<HandEvent.PlayerActed>> copiedActions = new LinkedHashMap<>();
        roundActions.forEach((seat, actions) -> copiedActions.put(seat, List.copyOf(actions)));
        roundActions = Map.copyOf(copiedActions);
        handHistories = List.copyOf(handHistories);
    }
}
