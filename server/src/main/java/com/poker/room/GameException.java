package com.poker.room;

public final class GameException extends RuntimeException {
    private final GameError error;
    public GameException(GameError error) { super(error.name()); this.error = error; }
    public GameException(GameError error, Throwable cause) { super(error.name(), cause); this.error = error; }
    public GameError error() { return error; }
}
