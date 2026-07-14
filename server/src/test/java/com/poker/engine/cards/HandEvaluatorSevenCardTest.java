package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandEvaluatorSevenCardTest {
    @Test
    void choosesBestFiveRegardlessOfInputOrder() {
        HandRank hand = evaluate("2c 3d As Ks Qs Js Ts");
        assertEquals(HandCategory.STRAIGHT_FLUSH, hand.category());
        assertEquals(List.of(Rank.ACE), hand.tieBreakRanks());
    }

    @Test
    void returnsTheCardsUsedByTheBestFiveCardHand() {
        assertEquals(
                HandEvaluatorCategoryTest.cards("As Ks Qs Js Ts"),
                HandEvaluator.bestFive(HandEvaluatorCategoryTest.cards("2c 3d As Ks Qs Js Ts")));
    }

    @Test
    void ordersBestComboByGroupsAndHighCards() {
        assertBestFive("As 5d 2c 5h 5c 3s 5s", "5d 5h 5c 5s As");
        assertBestFive("Ks Qs Kd 2h Qd Qc 3c", "Qs Qd Qc Ks Kd");
        assertBestFive("7s 2d 7c Ah 3s 7d 4c", "7s 7c 7d Ah 4c");
        assertBestFive("Ks 2c 4s Kd Ah 4h 3d", "Ks Kd 4s 4h Ah");
        assertBestFive("Qs 2c Qd Ah 8d 4s Jc", "Qs Qd Ah Jc 8d");
        assertBestFive("2c Ah 9d 4s Jc 6h 3d", "Ah Jc 9d 6h 4s");
        assertBestFive("4h Ah 8h 2h Jh Kc 3d", "Ah Jh 8h 4h 2h");
    }

    @Test
    void supportsBoardPlayingAndHoleCardImprovement() {
        List<Card> board = HandEvaluatorCategoryTest.cards("9c 8d 7s 6h 5c");
        ArrayList<Card> first = new ArrayList<>(board); first.addAll(HandEvaluatorCategoryTest.cards("As Ad"));
        ArrayList<Card> second = new ArrayList<>(board); second.addAll(HandEvaluatorCategoryTest.cards("Kc Qc"));
        assertEquals(HandEvaluator.evaluate7(first), HandEvaluator.evaluate7(second));

        HandRank boardWithKicker = evaluate("As Jd 9c 6h 3s Kc 2d");
        assertEquals(List.of(Rank.ACE, Rank.KING, Rank.JACK, Rank.NINE, Rank.SIX), boardWithKicker.tieBreakRanks());
        assertEquals(HandCategory.TWO_PAIR, evaluate("As Ad Ks Kd 2c Qh Qs").category());
    }

    @Test
    void selectsCanonicalFullHousePairsFlushAndStraight() {
        HandRank twoTrips = evaluate("Ks Kd Kc Qs Qd Qc 2h");
        assertEquals(List.of(Rank.KING, Rank.QUEEN), twoTrips.tieBreakRanks());
        HandRank threePairs = evaluate("As Ad Ks Kd Qs Qd 2h");
        assertEquals(List.of(Rank.ACE, Rank.KING, Rank.QUEEN), threePairs.tieBreakRanks());
        HandRank flush = evaluate("As Js 9s 6s 4s 2s Kd");
        assertEquals(List.of(Rank.ACE, Rank.JACK, Rank.NINE, Rank.SIX, Rank.FOUR), flush.tieBreakRanks());
        assertEquals(List.of(Rank.TEN), evaluate("Ts 9d 8c 7h 6s 5d 4c").tieBreakRanks());
    }

    @Test
    void neverCombinesOffSuitCardsIntoStraightFlush() {
        assertEquals(HandCategory.FLUSH, evaluate("Ah Jh 9h 6h 3h 8c 7d").category());
    }

    @Test
    void rejectsMalformedInputs() {
        assertThrows(NullPointerException.class, () -> HandEvaluator.evaluate7(null));
        assertThrows(IllegalArgumentException.class, () -> HandEvaluator.evaluate7(HandEvaluatorCategoryTest.cards("As Ks Qs Js Ts 9s")));
        assertThrows(IllegalArgumentException.class, () -> HandEvaluator.evaluate7(HandEvaluatorCategoryTest.cards("As Ks Qs Js Ts 9s 8s 7s")));
        ArrayList<Card> duplicate = new ArrayList<>(HandEvaluatorCategoryTest.cards("As Ks Qs Js Ts 9s")); duplicate.add(Card.parse("As"));
        assertThrows(IllegalArgumentException.class, () -> HandEvaluator.evaluate7(duplicate));
        ArrayList<Card> withNull = new ArrayList<>(HandEvaluatorCategoryTest.cards("As Ks Qs Js Ts 9s")); withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> HandEvaluator.evaluate7(withNull));
        assertThrows(IllegalArgumentException.class, () -> HandEvaluator.rank5(HandEvaluatorCategoryTest.cards("As Ks Qs Js")));
    }

    private static HandRank evaluate(String text) { return HandEvaluator.evaluate7(HandEvaluatorCategoryTest.cards(text)); }

    private static void assertBestFive(String sevenCards, String expectedFiveCards) {
        assertEquals(
                HandEvaluatorCategoryTest.cards(expectedFiveCards),
                HandEvaluator.bestFive(HandEvaluatorCategoryTest.cards(sevenCards)));
    }
}
