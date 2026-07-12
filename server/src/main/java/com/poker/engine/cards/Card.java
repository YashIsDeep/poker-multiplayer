package com.poker.engine.cards;

import java.util.Objects;

public record Card(int code) {
    public Card {
        if (code < 0 || code >= 52) {
            throw new IllegalArgumentException("Card code must be between 0 and 51: " + code);
        }
    }

    public static Card of(Rank rank, Suit suit) {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
        return new Card((rank.value() - 2) * 4 + suit.code());
    }

    public static Card parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != 2) {
            throw new IllegalArgumentException("Card text must contain exactly two characters: " + text);
        }
        return of(Rank.fromSymbol(text.charAt(0)), Suit.fromSymbol(text.charAt(1)));
    }

    public Rank rank() { return Rank.fromValue((code >> 2) + 2); }
    public Suit suit() { return Suit.fromCode(code & 3); }

    @Override
    public String toString() {
        return new String(new char[] { rank().symbol(), suit().symbol() });
    }
}
