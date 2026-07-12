package com.poker.engine.game;

import java.util.List;
import java.util.Objects;

public record Pot(
        long amount,
        long cap,
        List<PlayerId> contributors,
        List<PlayerId> eligible
) {
    public Pot {
        if (amount <= 0 || cap <= 0) {
            throw new IllegalArgumentException("pot amount and cap must be positive");
        }
        Objects.requireNonNull(contributors, "contributors");
        Objects.requireNonNull(eligible, "eligible");
        if (contributors.stream().anyMatch(Objects::isNull) || eligible.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("pot player IDs cannot be null");
        }
        contributors = List.copyOf(contributors);
        eligible = List.copyOf(eligible);
    }

    public long contributionCap() {
        return cap;
    }

    public List<PlayerId> contributorIds() {
        return contributors;
    }

    public List<PlayerId> eligibleIds() {
        return eligible;
    }

    public List<PlayerId> eligiblePlayerIds() {
        return eligible;
    }
}
