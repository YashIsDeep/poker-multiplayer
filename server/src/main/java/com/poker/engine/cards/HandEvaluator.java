package com.poker.engine.cards;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class HandEvaluator {
    private HandEvaluator() {}

    public static HandRank rank5(List<Card> cards) {
        validate(cards, 5);
        return rankTrusted(cards.get(0), cards.get(1), cards.get(2), cards.get(3), cards.get(4));
    }

    public static HandRank evaluate7(List<Card> cards) {
        validate(cards, 7);
        HandRank best = null;
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 4; b++) {
                for (int c = b + 1; c < 5; c++) {
                    for (int d = c + 1; d < 6; d++) {
                        for (int e = d + 1; e < 7; e++) {
                            HandRank candidate = rankTrusted(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e));
                            if (best == null || candidate.compareTo(best) > 0) best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void validate(List<Card> cards, int expectedSize) {
        Objects.requireNonNull(cards, "cards");
        if (cards.size() != expectedSize) {
            throw new IllegalArgumentException("Expected exactly " + expectedSize + " cards, got " + cards.size());
        }
        HashSet<Card> unique = new HashSet<>(expectedSize);
        for (Card card : cards) {
            if (card == null) throw new IllegalArgumentException("Cards cannot contain null");
            if (!unique.add(card)) throw new IllegalArgumentException("Duplicate card: " + card);
        }
    }

    private static HandRank rankTrusted(Card... cards) {
        int[] counts = new int[15];
        int[] suits = new int[4];
        int mask = 0;
        for (Card card : cards) {
            int rank = card.rank().value();
            counts[rank]++;
            suits[card.suit().code()]++;
            mask |= 1 << (rank - 2);
        }

        boolean flush = false;
        for (int count : suits) if (count == 5) flush = true;
        int straightHigh = straightHigh(mask);

        ArrayList<Rank> quads = new ArrayList<>(1);
        ArrayList<Rank> trips = new ArrayList<>(1);
        ArrayList<Rank> pairs = new ArrayList<>(2);
        ArrayList<Rank> singles = new ArrayList<>(5);
        for (int value = 14; value >= 2; value--) {
            Rank rank = Rank.fromValue(value);
            switch (counts[value]) {
                case 4 -> quads.add(rank);
                case 3 -> trips.add(rank);
                case 2 -> pairs.add(rank);
                case 1 -> singles.add(rank);
                default -> { }
            }
        }

        if (flush && straightHigh != 0) return hand(HandCategory.STRAIGHT_FLUSH, straightHigh);
        if (!quads.isEmpty()) return new HandRank(HandCategory.FOUR_OF_A_KIND, List.of(quads.get(0), singles.get(0)));
        if (!trips.isEmpty() && !pairs.isEmpty()) return new HandRank(HandCategory.FULL_HOUSE, List.of(trips.get(0), pairs.get(0)));
        if (flush) return new HandRank(HandCategory.FLUSH, singles);
        if (straightHigh != 0) return hand(HandCategory.STRAIGHT, straightHigh);
        if (!trips.isEmpty()) return new HandRank(HandCategory.THREE_OF_A_KIND, List.of(trips.get(0), singles.get(0), singles.get(1)));
        if (pairs.size() == 2) return new HandRank(HandCategory.TWO_PAIR, List.of(pairs.get(0), pairs.get(1), singles.get(0)));
        if (pairs.size() == 1) return new HandRank(HandCategory.ONE_PAIR, List.of(pairs.get(0), singles.get(0), singles.get(1), singles.get(2)));
        return new HandRank(HandCategory.HIGH_CARD, singles);
    }

    private static HandRank hand(HandCategory category, int high) {
        return new HandRank(category, List.of(Rank.fromValue(high)));
    }

    private static int straightHigh(int mask) {
        for (int high = 14; high >= 6; high--) {
            int run = 0b1_1111 << (high - 6);
            if ((mask & run) == run) return high;
        }
        int wheel = (1 << (14 - 2)) | 0b1111;
        return (mask & wheel) == wheel ? 5 : 0;
    }
}
