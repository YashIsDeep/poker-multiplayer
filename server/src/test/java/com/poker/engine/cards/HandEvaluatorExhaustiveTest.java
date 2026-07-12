package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandEvaluatorExhaustiveTest {
    @Test
    void allFiveCardHandsMatchKnownCategoryDistribution() {
        EnumMap<HandCategory, Integer> counts = new EnumMap<>(HandCategory.class);
        Card[] deck = new Card[52];
        for (int i = 0; i < deck.length; i++) deck[i] = new Card(i);
        int total = 0;
        for (int a = 0; a < 48; a++)
            for (int b = a + 1; b < 49; b++)
                for (int c = b + 1; c < 50; c++)
                    for (int d = c + 1; d < 51; d++)
                        for (int e = d + 1; e < 52; e++) {
                            HandCategory category = HandEvaluator.rank5(List.of(deck[a], deck[b], deck[c], deck[d], deck[e])).category();
                            counts.merge(category, 1, Integer::sum);
                            total++;
                        }
        assertEquals(2_598_960, total);
        assertEquals(1_302_540, counts.get(HandCategory.HIGH_CARD));
        assertEquals(1_098_240, counts.get(HandCategory.ONE_PAIR));
        assertEquals(123_552, counts.get(HandCategory.TWO_PAIR));
        assertEquals(54_912, counts.get(HandCategory.THREE_OF_A_KIND));
        assertEquals(10_200, counts.get(HandCategory.STRAIGHT));
        assertEquals(5_108, counts.get(HandCategory.FLUSH));
        assertEquals(3_744, counts.get(HandCategory.FULL_HOUSE));
        assertEquals(624, counts.get(HandCategory.FOUR_OF_A_KIND));
        assertEquals(40, counts.get(HandCategory.STRAIGHT_FLUSH));
    }
}
