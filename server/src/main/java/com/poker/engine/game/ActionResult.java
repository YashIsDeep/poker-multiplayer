package com.poker.engine.game;

import java.util.List;
import java.util.Objects;

public record ActionResult(HandSnapshot snapshot, List<HandTransition> transitions) {
    public ActionResult {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(transitions, "transitions");
        if (transitions.isEmpty() || transitions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("an accepted action requires transitions");
        }
        if (!snapshot.equals(transitions.get(transitions.size() - 1).snapshotAfter())) {
            throw new IllegalArgumentException("result snapshot must equal the final transition frame");
        }
        transitions = List.copyOf(transitions);
    }
}
