package com.poker.room;

/** Immutable table rules. All chip arithmetic is signed-long and validated up front. */
public record RoomConfig(
        long smallBlind,
        long bigBlind,
        long minBuyIn,
        long maxBuyIn,
        int actionTimeoutSeconds) {
    public static final RoomConfig DEFAULT = new RoomConfig(5, 10, 200, 2_000, 30);

    public RoomConfig {
        if (smallBlind <= 0 || bigBlind < smallBlind) throw new RoomException(RoomError.INVALID_ROOM_CONFIG);
        long minimum;
        try {
            minimum = Math.multiplyExact(20L, bigBlind);
        } catch (ArithmeticException ex) {
            throw new RoomException(RoomError.INVALID_ROOM_CONFIG, ex);
        }
        if (minBuyIn < minimum || maxBuyIn < minBuyIn || actionTimeoutSeconds < 10 || actionTimeoutSeconds > 120) {
            throw new RoomException(RoomError.INVALID_ROOM_CONFIG);
        }
    }

    public void validateBuyIn(long buyIn) {
        if (buyIn < minBuyIn || buyIn > maxBuyIn) throw new RoomException(RoomError.INVALID_BUY_IN);
    }
}
