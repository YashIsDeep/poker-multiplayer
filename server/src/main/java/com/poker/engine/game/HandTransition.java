package com.poker.engine.game;

import java.util.List;
import java.util.Objects;

public record HandTransition(long sequence, List<HandEvent> events, HandSnapshot snapshotAfter) {
    public HandTransition {
        if (sequence <= 0) throw new IllegalArgumentException("transition sequence must be positive");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(snapshotAfter, "snapshotAfter");
        if (events.isEmpty() || events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("a transition requires nonempty events");
        }
        events = List.copyOf(events);
    }
}
