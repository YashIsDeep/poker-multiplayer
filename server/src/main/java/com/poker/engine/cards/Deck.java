package com.poker.engine.cards;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class Deck {
    private static final int SIZE = 52;
    private final Card[] cards;
    private int cursor;

    private Deck(Card[] cards) {
        this.cards = cards;
    }

    public static Deck shuffled(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        Card[] cards = new Card[SIZE];
        for (int code = 0; code < SIZE; code++) cards[code] = new Card(code);
        for (int i = cards.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Card temporary = cards[i];
            cards[i] = cards[j];
            cards[j] = temporary;
        }
        return new Deck(cards);
    }

    public static Deck of(List<Card> drawOrder) {
        Objects.requireNonNull(drawOrder, "drawOrder");
        if (drawOrder.size() != SIZE) {
            throw new IllegalArgumentException("A deck must contain exactly 52 cards");
        }
        Card[] cards = new Card[SIZE];
        HashSet<Card> unique = new HashSet<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            Card card = drawOrder.get(i);
            if (card == null) throw new IllegalArgumentException("Deck contains a null card");
            if (!unique.add(card)) throw new IllegalArgumentException("Deck contains duplicate card: " + card);
            cards[i] = card;
        }
        return new Deck(cards);
    }

    public Card draw() {
        if (cursor == cards.length) throw new NoSuchElementException("Deck is empty");
        return cards[cursor++];
    }

    public List<Card> draw(int count) {
        if (count < 0 || count > remaining()) {
            throw new IllegalArgumentException("Cannot draw " + count + " cards with " + remaining() + " remaining");
        }
        ArrayList<Card> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) drawn.add(draw());
        return List.copyOf(drawn);
    }

    public int remaining() { return cards.length - cursor; }
}
