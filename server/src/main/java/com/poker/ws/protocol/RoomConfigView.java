package com.poker.ws.protocol;

public record RoomConfigView(
        long smallBlind,
        long bigBlind,
        long minBuyIn,
        long maxBuyIn,
        int actionTimeoutSeconds) {}
