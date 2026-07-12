package com.poker.room;

import java.util.Locale;
import java.util.Objects;

/** Canonical six-character code used to discover a room. */
public record JoinCode(String value) {
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;

    public JoinCode {
        Objects.requireNonNull(value, "value");
        value = canonicalize(value);
        if (value.length() != LENGTH || value.chars().anyMatch(c -> ALPHABET.indexOf(c) < 0)) {
            throw new IllegalArgumentException("invalid join code");
        }
    }

    public static JoinCode parse(String raw) {
        return new JoinCode(raw);
    }

    public static JoinCode of(String raw) {
        return parse(raw);
    }

    private static String canonicalize(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
