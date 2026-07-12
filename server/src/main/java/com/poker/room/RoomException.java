package com.poker.room;

import java.util.Objects;

public final class RoomException extends RuntimeException {
    private final RoomError error;

    public RoomException(RoomError error) {
        super(Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    public RoomException(RoomError error, Throwable cause) {
        super(Objects.requireNonNull(error, "error").name(), cause);
        this.error = error;
    }

    public RoomError error() {
        return error;
    }
}
