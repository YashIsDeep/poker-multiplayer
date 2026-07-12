package com.poker.engine.cards;

public enum Rank {
    TWO(2, '2'), THREE(3, '3'), FOUR(4, '4'), FIVE(5, '5'), SIX(6, '6'),
    SEVEN(7, '7'), EIGHT(8, '8'), NINE(9, '9'), TEN(10, 'T'),
    JACK(11, 'J'), QUEEN(12, 'Q'), KING(13, 'K'), ACE(14, 'A');

    private final int value;
    private final char symbol;

    Rank(int value, char symbol) {
        this.value = value;
        this.symbol = symbol;
    }

    public int value() { return value; }
    public char symbol() { return symbol; }

    public static Rank fromSymbol(char symbol) {
        char normalized = Character.toUpperCase(symbol);
        for (Rank rank : values()) {
            if (rank.symbol == normalized) return rank;
        }
        throw new IllegalArgumentException("Unknown rank symbol: " + symbol);
    }

    public static Rank fromValue(int value) {
        if (value < 2 || value > 14) {
            throw new IllegalArgumentException("Rank value must be between 2 and 14: " + value);
        }
        return values()[value - 2];
    }
}
