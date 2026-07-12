package com.poker.engine.cards;

public enum Suit {
    CLUBS(0, 'c'), DIAMONDS(1, 'd'), HEARTS(2, 'h'), SPADES(3, 's');

    private final int code;
    private final char symbol;

    Suit(int code, char symbol) {
        this.code = code;
        this.symbol = symbol;
    }

    public int code() { return code; }
    public char symbol() { return symbol; }

    public static Suit fromSymbol(char symbol) {
        char normalized = Character.toLowerCase(symbol);
        for (Suit suit : values()) {
            if (suit.symbol == normalized) return suit;
        }
        throw new IllegalArgumentException("Unknown suit symbol: " + symbol);
    }

    public static Suit fromCode(int code) {
        if (code < 0 || code > 3) {
            throw new IllegalArgumentException("Suit code must be between 0 and 3: " + code);
        }
        return values()[code];
    }
}
