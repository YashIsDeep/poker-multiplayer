package com.poker.ws.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Viewer-safe, structured hand history returned only when the history panel is opened. */
public record HandHistoryView(
        long handId,
        Instant startedAt,
        Instant completedAt,
        String phase,
        String completionReason,
        int buttonSeat,
        int smallBlindSeat,
        int bigBlindSeat,
        long smallBlind,
        long bigBlind,
        List<String> board,
        List<PlayerView> players,
        List<EventView> events) {
    public HandHistoryView {
        board = List.copyOf(board);
        players = List.copyOf(players);
        events = List.copyOf(events);
    }

    public record PlayerView(
            int seat,
            UUID userId,
            String displayName,
            long startingStack,
            long endingStack,
            long netChange,
            long won,
            boolean folded,
            List<String> holeCards,
            String finalHandRank) {
        public PlayerView {
            holeCards = holeCards == null ? null : List.copyOf(holeCards);
        }
    }

    public record EventView(
            long sequence,
            Instant occurredAt,
            String phase,
            String type,
            Integer seat,
            String action,
            Long amount,
            Long total,
            Long stackAfter,
            List<String> cards,
            Integer potIndex,
            Long potAmount,
            List<WinnerView> winners) {
        public EventView {
            cards = List.copyOf(cards);
            winners = List.copyOf(winners);
        }
    }

    public record WinnerView(int seat, long amount) {}
}
