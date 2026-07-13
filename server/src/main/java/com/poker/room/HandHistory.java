package com.poker.room;

import com.poker.engine.game.HandSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, trusted audit trail for one table hand. It is redacted before leaving the server. */
public record HandHistory(
        long handId,
        Instant startedAt,
        Instant completedAt,
        long smallBlind,
        long bigBlind,
        List<Player> players,
        List<HandHistoryEvent> events,
        HandSnapshot latestSnapshot) {
    public HandHistory {
        if (handId <= 0 || smallBlind <= 0 || bigBlind <= smallBlind) {
            throw new IllegalArgumentException("invalid hand history identity or blinds");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(latestSnapshot, "latestSnapshot");
        players = List.copyOf(players);
        events = List.copyOf(events);
    }

    public record Player(UUID userId, int seat, long startingStack) {
        public Player {
            Objects.requireNonNull(userId, "userId");
            if (seat < 0 || startingStack <= 0) {
                throw new IllegalArgumentException("invalid hand-history player");
            }
        }
    }
}
