package com.poker.engine.game;

import java.util.Objects;

/** Stable engine identity kept independent from auth and persistence types. */
public record PlayerId(String value) {
    public PlayerId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PlayerId must not be blank");
        }
    }

    public static PlayerId of(String value) {
        return new PlayerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
