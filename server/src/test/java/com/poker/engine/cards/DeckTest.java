package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class DeckTest {
    @Test
    void shuffleIsCompleteUniqueAndDeterministic() {
        Deck first = Deck.shuffled(new Random(1234));
        Deck second = Deck.shuffled(new Random(1234));
        assertEquals(52, first.remaining());
        List<Card> firstOrder = first.draw(52);
        assertEquals(firstOrder, second.draw(52));
        assertEquals(52, new HashSet<>(firstOrder).size());
        assertEquals(0, first.remaining());
        assertThrows(NoSuchElementException.class, first::draw);
    }

    @Test
    void fisherYatesUsesEveryShrinkingInclusiveBound() {
        RecordingRandom random = new RecordingRandom();
        Deck.shuffled(random);
        List<Integer> expected = new ArrayList<>();
        for (int bound = 52; bound >= 2; bound--) expected.add(bound);
        assertEquals(expected, random.bounds);
    }

    @Test
    void riggedDeckCopiesAndPreservesDrawOrder() {
        ArrayList<Card> order = orderedCards();
        List<Card> expected = List.copyOf(order);
        Deck deck = Deck.of(order);
        order.set(0, order.get(1));
        assertEquals(expected.get(0), deck.draw());
        assertEquals(51, deck.remaining());
        assertEquals(expected.subList(1, 52), deck.draw(51));
    }

    @Test
    void batchDrawValidatesAndReturnsImmutableSnapshots() {
        Deck deck = Deck.of(orderedCards());
        assertTrue(deck.draw(0).isEmpty());
        assertEquals(52, deck.remaining());
        List<Card> drawn = deck.draw(2);
        assertEquals(50, deck.remaining());
        assertThrows(UnsupportedOperationException.class, () -> drawn.add(new Card(2)));
        assertThrows(IllegalArgumentException.class, () -> deck.draw(-1));
        assertThrows(IllegalArgumentException.class, () -> deck.draw(51));
        assertThrows(NullPointerException.class, () -> Deck.shuffled(null));
    }

    @Test
    void riggedDeckRejectsMalformedOrders() {
        assertThrows(NullPointerException.class, () -> Deck.of(null));
        assertThrows(IllegalArgumentException.class, () -> Deck.of(List.of(new Card(0))));
        ArrayList<Card> withNull = orderedCards();
        withNull.set(3, null);
        assertThrows(IllegalArgumentException.class, () -> Deck.of(withNull));
        ArrayList<Card> duplicate = orderedCards();
        duplicate.set(3, duplicate.get(2));
        assertThrows(IllegalArgumentException.class, () -> Deck.of(duplicate));
    }

    private static ArrayList<Card> orderedCards() {
        ArrayList<Card> cards = new ArrayList<>();
        for (int code = 0; code < 52; code++) cards.add(new Card(code));
        return cards;
    }

    private static final class RecordingRandom implements RandomGenerator {
        private final List<Integer> bounds = new ArrayList<>();
        @Override public int nextInt(int bound) { bounds.add(bound); return 0; }
        @Override public long nextLong() { return 0; }
    }
}
