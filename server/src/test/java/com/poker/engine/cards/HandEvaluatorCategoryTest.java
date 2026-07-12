package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandEvaluatorCategoryTest {
    @Test
    void ranksEveryCategoryWithCanonicalTieBreakVector() {
        assertHand("As Jd 9c 6h 3s", HandCategory.HIGH_CARD, Rank.ACE, Rank.JACK, Rank.NINE, Rank.SIX, Rank.THREE);
        assertHand("Qs Qc Ah 8d 2s", HandCategory.ONE_PAIR, Rank.QUEEN, Rank.ACE, Rank.EIGHT, Rank.TWO);
        assertHand("Ks Kd 4c 4h 9s", HandCategory.TWO_PAIR, Rank.KING, Rank.FOUR, Rank.NINE);
        assertHand("7s 7d 7c Ah 3s", HandCategory.THREE_OF_A_KIND, Rank.SEVEN, Rank.ACE, Rank.THREE);
        assertHand("9s 8d 7c 6h 5s", HandCategory.STRAIGHT, Rank.NINE);
        assertHand("Ah Jh 8h 4h 2h", HandCategory.FLUSH, Rank.ACE, Rank.JACK, Rank.EIGHT, Rank.FOUR, Rank.TWO);
        assertHand("Ts Td Tc 6h 6s", HandCategory.FULL_HOUSE, Rank.TEN, Rank.SIX);
        assertHand("5s 5d 5c 5h As", HandCategory.FOUR_OF_A_KIND, Rank.FIVE, Rank.ACE);
        assertHand("9h 8h 7h 6h 5h", HandCategory.STRAIGHT_FLUSH, Rank.NINE);
    }

    @Test
    void handlesWheelRoyalAndNearStraightEdges() {
        assertHand("As 2d 3c 4h 5s", HandCategory.STRAIGHT, Rank.FIVE);
        assertHand("As 2s 3s 4s 5s", HandCategory.STRAIGHT_FLUSH, Rank.FIVE);
        assertHand("As Ks Qs Js Ts", HandCategory.STRAIGHT_FLUSH, Rank.ACE);
        assertEquals(HandCategory.HIGH_CARD, rank("As 2d 3c 4h 6s").category());
        assertEquals(HandCategory.FLUSH, rank("Ah Jh 9h 8h 7h").category());
    }

    @Test
    void descriptionsMatchThePublicContract() {
        assertEquals("High Card, Ace", rank("As Jd 9c 6h 3s").describe());
        assertEquals("Pair of Queens", rank("Qs Qc Ah 8d 2s").describe());
        assertEquals("Two Pair, Kings and Fours", rank("Ks Kd 4c 4h 9s").describe());
        assertEquals("Three of a Kind, Sevens", rank("7s 7d 7c Ah 3s").describe());
        assertEquals("Nine-high Straight", rank("9s 8d 7c 6h 5s").describe());
        assertEquals("Ace-high Flush", rank("Ah Jh 8h 4h 2h").describe());
        assertEquals("Tens full of Sixes", rank("Ts Td Tc 6h 6s").describe());
        assertEquals("Four of a Kind, Fives", rank("5s 5d 5c 5h As").describe());
        assertEquals("Nine-high Straight Flush", rank("9h 8h 7h 6h 5h").describe());
        assertEquals("Five-high Straight", rank("As 2d 3c 4h 5s").describe());
        assertEquals("Five-high Straight Flush", rank("As 2s 3s 4s 5s").describe());
        assertEquals("Ace-high Straight Flush", rank("As Ks Qs Js Ts").describe());
    }

    private static void assertHand(String text, HandCategory category, Rank... ranks) {
        HandRank hand = rank(text);
        assertEquals(category, hand.category());
        assertEquals(List.of(ranks), hand.tieBreakRanks());
        assertThrows(UnsupportedOperationException.class, () -> hand.tieBreakRanks().add(Rank.TWO));
    }

    private static HandRank rank(String text) { return HandEvaluator.rank5(cards(text)); }
    static List<Card> cards(String text) { return Arrays.stream(text.split(" ")).map(Card::parse).toList(); }
}
