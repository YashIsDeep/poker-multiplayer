package com.poker.engine.game;

public sealed interface PlayerAction permits PlayerAction.Fold, PlayerAction.Check, PlayerAction.Call, PlayerAction.RaiseTo {
    record Fold() implements PlayerAction {}
    record Check() implements PlayerAction {}
    record Call() implements PlayerAction {}
    record RaiseTo(long amount) implements PlayerAction {}
}
