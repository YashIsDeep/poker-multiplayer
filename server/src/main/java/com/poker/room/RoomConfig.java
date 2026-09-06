package com.poker.room;

/** Immutable table rules. All chip arithmetic is signed-long and validated up front. */
public record RoomConfig(
        long smallBlind,
        long bigBlind,
        long minBuyIn,
        long maxBuyIn,
        int actionTimeoutSeconds,
        long startingStack,
        boolean rebuysEnabled,
        long rebuyStack,
        int rebuyDelayHands) {
    public static final int MIN_ACTION_TIMEOUT_SECONDS = 10;
    public static final int MAX_ACTION_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_ACTION_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_REBUY_DELAY_HANDS = 5;
    public static final int MAX_REBUY_DELAY_HANDS = 20;
    public static final RoomConfig PUBLIC_DEFAULT = table(
            10, 1_000, true, 1_000, DEFAULT_REBUY_DELAY_HANDS);
    public static final RoomConfig DEFAULT = new RoomConfig(5, 10, 200, 2_000,
            DEFAULT_ACTION_TIMEOUT_SECONDS);

    /** Backwards-compatible constructor for engine and room tests using a buy-in range. */
    public RoomConfig(long smallBlind, long bigBlind, long minBuyIn, long maxBuyIn,
                      int actionTimeoutSeconds) {
        this(smallBlind, bigBlind, minBuyIn, maxBuyIn, actionTimeoutSeconds,
                minBuyIn, true, minBuyIn, DEFAULT_REBUY_DELAY_HANDS);
    }

    public RoomConfig {
        if (smallBlind <= 0 || bigBlind < smallBlind) throw new RoomException(RoomError.INVALID_ROOM_CONFIG);
        long minimum;
        try {
            minimum = Math.multiplyExact(20L, bigBlind);
        } catch (ArithmeticException ex) {
            throw new RoomException(RoomError.INVALID_ROOM_CONFIG, ex);
        }
        if (minBuyIn < minimum || maxBuyIn < minBuyIn
                || actionTimeoutSeconds < MIN_ACTION_TIMEOUT_SECONDS
                || actionTimeoutSeconds > MAX_ACTION_TIMEOUT_SECONDS
                || startingStack < minBuyIn || startingStack > maxBuyIn
                || rebuyStack <= 0 || rebuyDelayHands < 0
                || rebuyDelayHands > MAX_REBUY_DELAY_HANDS) {
            throw new RoomException(RoomError.INVALID_ROOM_CONFIG);
        }
    }

    public static RoomConfig table(long bigBlind, long startingStack,
                                   boolean rebuysEnabled, long rebuyStack) {
        return table(bigBlind, startingStack, rebuysEnabled, rebuyStack,
                DEFAULT_REBUY_DELAY_HANDS);
    }

    public static RoomConfig table(long bigBlind, long startingStack,
                                   boolean rebuysEnabled, long rebuyStack,
                                   int rebuyDelayHands) {
        return table(bigBlind, startingStack, rebuysEnabled, rebuyStack,
                DEFAULT_ACTION_TIMEOUT_SECONDS, rebuyDelayHands);
    }

    public static RoomConfig table(long bigBlind, long startingStack,
                                   boolean rebuysEnabled, long rebuyStack,
                                   int actionTimeoutSeconds, int rebuyDelayHands) {
        long smallBlind = Math.max(1, bigBlind / 2);
        return new RoomConfig(
                smallBlind, bigBlind, startingStack, startingStack, actionTimeoutSeconds,
                startingStack, rebuysEnabled, rebuyStack, rebuyDelayHands);
    }

    public void validateBuyIn(long buyIn) {
        if (buyIn < minBuyIn || buyIn > maxBuyIn) throw new RoomException(RoomError.INVALID_BUY_IN);
    }
}
