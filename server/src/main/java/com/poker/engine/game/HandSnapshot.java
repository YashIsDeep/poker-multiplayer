package com.poker.engine.game;

import com.poker.engine.cards.Card;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record HandSnapshot(
        HandPhase phase,
        Optional<CompletionReason> completionReason,
        int buttonSeat,
        int smallBlindSeat,
        int bigBlindSeat,
        OptionalInt actingSeat,
        long currentBet,
        long lastFullRaiseSize,
        List<PlayerSnapshot> players,
        List<Card> board,
        List<Pot> pots,
        Optional<PendingUnmatched> pendingUnmatched,
        List<PotAward> awards,
        long totalChipsAtStart
) {
    public HandSnapshot {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(completionReason, "completionReason");
        Objects.requireNonNull(actingSeat, "actingSeat");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(pots, "pots");
        Objects.requireNonNull(pendingUnmatched, "pendingUnmatched");
        Objects.requireNonNull(awards, "awards");
        if (currentBet < 0 || lastFullRaiseSize <= 0 || totalChipsAtStart <= 0) {
            throw new IllegalArgumentException("invalid hand snapshot amounts");
        }
        if (completionReason.isPresent() != (phase == HandPhase.COMPLETE)) {
            throw new IllegalArgumentException("completion reason must match phase");
        }
        if (players.isEmpty() || players.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("hand snapshots require players");
        }
        if (board.stream().anyMatch(Objects::isNull) || new HashSet<>(board).size() != board.size()) {
            throw new IllegalArgumentException("board cards must be unique and non-null");
        }
        if (pots.stream().anyMatch(Objects::isNull) || awards.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("snapshot collections cannot contain null");
        }
        players = List.copyOf(players);
        board = List.copyOf(board);
        pots = List.copyOf(pots);
        awards = List.copyOf(awards);
    }

    public long currentStreetTarget() {
        return currentBet;
    }

    public OptionalInt actorSeat() {
        return actingSeat;
    }

    public List<PlayerSnapshot> playerSnapshots() {
        return players;
    }

    public List<Card> publicBoard() {
        return board;
    }

    public List<Pot> matchedPots() {
        return pots;
    }
}
