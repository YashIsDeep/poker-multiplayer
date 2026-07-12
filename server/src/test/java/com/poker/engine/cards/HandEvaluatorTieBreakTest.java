package com.poker.engine.cards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandEvaluatorTieBreakTest {
    @Test
    void categoriesHaveTotalStrengthOrder() {
        List<HandRank> ordered = List.of(
                rank("As Jd 9c 6h 3s"), rank("2s 2d 9c 6h 3s"), rank("3s 3d 2c 2h 4s"),
                rank("4s 4d 4c 6h 3s"), rank("6s 5d 4c 3h 2s"), rank("As Js 9s 6s 3s"),
                rank("5s 5d 5c 3h 3s"), rank("6s 6d 6c 6h 3s"), rank("7s 6s 5s 4s 3s"));
        for (int i = 1; i < ordered.size(); i++) assertTrue(ordered.get(i).compareTo(ordered.get(i - 1)) > 0);
    }

    @Test
    void comparesRepeatedRanksAndEveryKickerLexicographically() {
        assertHigher("Ks Kd 2c 3h 4s", "Qs Qd Ac Jh Ts");
        assertHigher("Qs Qd Ac Jh 9s", "Qs Qc Kh Jc 9d");
        assertHigher("Qs Qd Ac Jh 9s", "Qs Qc Ah Th 9d");
        assertHigher("Qs Qd Ac Jh 9s", "Qs Qc Ah Jc 8d");
        assertHigher("Ks Kd 4c 4h 2s", "Qs Qd Jc Jh As");
        assertHigher("Ks Kd 4c 4h 3s", "Ks Kc 3c 3h As");
        assertHigher("Ks Kd 4c 4h As", "Kh Kc 4s 4d Qs");
        assertHigher("7s 7d 7c Ah 4s", "6s 6d 6c Ah Ks");
        assertHigher("7s 7d 7c Ah 4s", "7h 7c 7d Kh Qs");
        assertHigher("7s 7d 7c Ah 4s", "7h 7c 7d Ac 3s");
        assertHigher("Ts Td Tc 6h 6s", "9s 9d 9c Ah As");
        assertHigher("Ts Td Tc 6h 6s", "Th Tc Ts 5h 5s");
        assertHigher("5s 5d 5c 5h As", "4s 4d 4c 4h As");
        assertHigher("5s 5d 5c 5h As", "5s 5d 5c 5h Ks");
    }

    @Test
    void comparesRunsAndFiveCardVectors() {
        assertHigher("6s 5d 4c 3h 2s", "As 2d 3c 4h 5s");
        assertHigher("6s 5s 4s 3s 2s", "As 2s 3s 4s 5s");
        assertHigher("As Qs 9s 5s 3s", "Ah Qh 9h 5h 2h");
        assertHigher("As Qd 9c 5h 3s", "Ah Qc 9d 5s 2c");
    }

    @Test
    void equalPokerHandsIgnoreSuitsAndRespectEqualityContract() {
        HandRank first = rank("As Jd 9c 6h 3s");
        HandRank second = rank("Ah Jc 9d 6s 3h");
        assertEquals(0, first.compareTo(second));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(-Integer.signum(first.compareTo(rank("Kh Qd 9c 6h 3s"))),
                Integer.signum(rank("Kh Qd 9c 6h 3s").compareTo(first)));
    }

    private static void assertHigher(String higher, String lower) { assertTrue(rank(higher).compareTo(rank(lower)) > 0); }
    private static HandRank rank(String text) { return HandEvaluator.rank5(HandEvaluatorCategoryTest.cards(text)); }
}
