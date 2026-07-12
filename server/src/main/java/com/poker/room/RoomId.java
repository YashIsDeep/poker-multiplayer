package com.poker.room;

import java.util.Objects;
import java.util.UUID;

public record RoomId(UUID value) {
    public RoomId {
        Objects.requireNonNull(value, "value");
    }

    public static RoomId random() {
        return new RoomId(UUID.randomUUID());
    }

    public static RoomId of(UUID value) {
        return new RoomId(value);
    }

    public static RoomId parse(String value) {
        return new RoomId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
