package com.poker.room;

import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandPhase;
import java.time.Instant;
import java.util.Objects;

/** One engine event annotated with the street and wall-clock time at which it became authoritative. */
public record HandHistoryEvent(
        long sequence,
        Instant occurredAt,
        HandPhase phase,
        HandEvent event) {
    public HandHistoryEvent {
        if (sequence <= 0) throw new IllegalArgumentException("history sequence must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(event, "event");
    }
}
