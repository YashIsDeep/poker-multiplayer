package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CardTest {
    @Test
    void allCodesRoundTripThroughModelAndText() {
        HashSet<Card> cards = new HashSet<>();
        for (int code = 0; code < 52; code++) {
            Card card = new Card(code);
            assertEquals(card, Card.of(card.rank(), card.suit()));
            assertEquals(card, Card.parse(card.toString()));
            cards.add(card);
        }
        assertEquals(52, cards.size());
    }

    @Test
    void encodingBoundariesAreRankMajor() {
        assertEquals("2c", new Card(0).toString());
        assertEquals("2s", new Card(3).toString());
        assertEquals("3c", new Card(4).toString());
        assertEquals("Ac", new Card(48).toString());
        assertEquals("As", new Card(51).toString());
    }

    @Test
    void parsingIsCaseInsensitiveAndRejectsInvalidInput() {
        assertEquals(Card.parse("as"), Card.parse("AS"));
        assertEquals("Th", Card.parse("tH").toString());
        assertThrows(NullPointerException.class, () -> Card.parse(null));
        for (String invalid : new String[] { "A", "Ace", "Xs", "Ax" }) {
            assertThrows(IllegalArgumentException.class, () -> Card.parse(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new Card(-1));
        assertThrows(IllegalArgumentException.class, () -> new Card(52));
    }
}
