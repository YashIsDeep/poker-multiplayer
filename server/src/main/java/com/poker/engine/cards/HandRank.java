package com.poker.engine.cards;

import java.util.List;
import java.util.Objects;

public final class HandRank implements Comparable<HandRank> {
    private final HandCategory category;
    private final List<Rank> tieBreakRanks;

    HandRank(HandCategory category, List<Rank> tieBreakRanks) {
        this.category = Objects.requireNonNull(category, "category");
        Objects.requireNonNull(tieBreakRanks, "tieBreakRanks");
        if (tieBreakRanks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Tie-break ranks cannot contain null");
        }
        int expected = switch (category) {
            case HIGH_CARD, FLUSH -> 5;
            case ONE_PAIR -> 4;
            case TWO_PAIR, THREE_OF_A_KIND -> 3;
            case FULL_HOUSE, FOUR_OF_A_KIND -> 2;
            case STRAIGHT, STRAIGHT_FLUSH -> 1;
        };
        if (tieBreakRanks.size() != expected) {
            throw new IllegalArgumentException(category + " requires " + expected + " tie-break ranks");
        }
        this.tieBreakRanks = List.copyOf(tieBreakRanks);
    }

    public HandCategory category() { return category; }
    public List<Rank> tieBreakRanks() { return tieBreakRanks; }

    @Override
    public int compareTo(HandRank other) {
        Objects.requireNonNull(other, "other");
        int categoryComparison = Integer.compare(category.strength(), other.category.strength());
        if (categoryComparison != 0) return categoryComparison;
        for (int i = 0; i < tieBreakRanks.size(); i++) {
            int rankComparison = Integer.compare(tieBreakRanks.get(i).value(), other.tieBreakRanks.get(i).value());
            if (rankComparison != 0) return rankComparison;
        }
        return 0;
    }

    public String describe() {
        Rank first = tieBreakRanks.get(0);
        return switch (category) {
            case HIGH_CARD -> "High Card, " + singular(first);
            case ONE_PAIR -> "Pair of " + plural(first);
            case TWO_PAIR -> "Two Pair, " + plural(first) + " and " + plural(tieBreakRanks.get(1));
            case THREE_OF_A_KIND -> "Three of a Kind, " + plural(first);
            case STRAIGHT -> singular(first) + "-high Straight";
            case FLUSH -> singular(first) + "-high Flush";
            case FULL_HOUSE -> plural(first) + " full of " + plural(tieBreakRanks.get(1));
            case FOUR_OF_A_KIND -> "Four of a Kind, " + plural(first);
            case STRAIGHT_FLUSH -> singular(first) + "-high Straight Flush";
        };
    }

    private static String singular(Rank rank) {
        return switch (rank) {
            case TWO -> "Two"; case THREE -> "Three"; case FOUR -> "Four"; case FIVE -> "Five";
            case SIX -> "Six"; case SEVEN -> "Seven"; case EIGHT -> "Eight"; case NINE -> "Nine";
            case TEN -> "Ten"; case JACK -> "Jack"; case QUEEN -> "Queen"; case KING -> "King"; case ACE -> "Ace";
        };
    }

    private static String plural(Rank rank) {
        return switch (rank) {
            case SIX -> "Sixes"; case TWO -> "Twos"; case THREE -> "Threes"; case FOUR -> "Fours";
            case FIVE -> "Fives"; case SEVEN -> "Sevens"; case EIGHT -> "Eights"; case NINE -> "Nines";
            case TEN -> "Tens"; case JACK -> "Jacks"; case QUEEN -> "Queens"; case KING -> "Kings"; case ACE -> "Aces";
        };
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof HandRank rank
                && category == rank.category && tieBreakRanks.equals(rank.tieBreakRanks);
    }

    @Override
    public int hashCode() { return Objects.hash(category, tieBreakRanks); }

    @Override
    public String toString() { return describe(); }
}
