package com.poker.engine.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic contribution-layer construction; it does not mutate players. */
final class PotSettlement {
    private PotSettlement() {}

    record View(List<Pot> pots, Optional<PendingUnmatched> pending) {
        View {
            Objects.requireNonNull(pots, "pots");
            Objects.requireNonNull(pending, "pending");
            pots = List.copyOf(pots);
        }
    }

    static View activeView(List<HoldemHand.PlayerState> players) {
        long highest = 0;
        long second = 0;
        HoldemHand.PlayerState leader = null;
        int leaders = 0;
        for (HoldemHand.PlayerState player : players) {
            long contribution = player.handCommitted;
            if (contribution > highest) {
                second = highest;
                highest = contribution;
                leader = player;
                leaders = 1;
            } else if (contribution == highest && contribution > 0) {
                leaders++;
            } else if (contribution > second) {
                second = contribution;
            }
        }

        long pendingAmount = leaders == 1 && highest > second ? highest - second : 0;
        long leaderEffective = highest - pendingAmount;
        ArrayList<Long> effective = new ArrayList<>(players.size());
        for (HoldemHand.PlayerState player : players) {
            effective.add(player == leader ? leaderEffective : player.handCommitted);
        }
        PendingUnmatched pending = pendingAmount == 0
                ? null
                : new PendingUnmatched(leader.id, leader.seat, pendingAmount);
        HoldemHand.PlayerState soleLive = null;
        int livePlayers = 0;
        for (HoldemHand.PlayerState player : players) {
            if (!player.folded) {
                livePlayers++;
                soleLive = player;
            }
        }
        boolean foldWinView = livePlayers == 1;
        if (foldWinView) {
            // A fold-win frame represents the whole committed pot. In
            // particular, a forced big blind must not appear as an uncalled
            // side amount when the small blind folds before voluntary action.
            return new View(settlementPots(players, true, soleLive), Optional.empty());
        }
        return new View(build(players, effective, false, null), Optional.ofNullable(pending));
    }

    static List<Pot> settlementPots(List<HoldemHand.PlayerState> players,
                                     boolean foldWin,
                                     HoldemHand.PlayerState foldWinner) {
        if (foldWin) return foldWinPot(players, foldWinner);
        ArrayList<Long> effective = new ArrayList<>(players.size());
        for (HoldemHand.PlayerState player : players) effective.add(player.handCommitted);
        return build(players, effective, foldWin, foldWinner);
    }

    private static List<Pot> foldWinPot(List<HoldemHand.PlayerState> players,
                                        HoldemHand.PlayerState foldWinner) {
        Objects.requireNonNull(foldWinner, "foldWinner");
        ArrayList<PlayerId> contributors = new ArrayList<>();
        long amount = 0;
        long cap = 0;
        for (HoldemHand.PlayerState player : players) {
            if (player.handCommitted <= 0) continue;
            amount = Math.addExact(amount, player.handCommitted);
            cap = Math.max(cap, player.handCommitted);
            contributors.add(player.id);
        }
        if (amount == 0) return List.of();
        return List.of(new Pot(amount, cap, contributors, List.of(foldWinner.id)));
    }

    private static List<Pot> build(List<HoldemHand.PlayerState> players,
                                  List<Long> effective,
                                  boolean foldWin,
                                  HoldemHand.PlayerState foldWinner) {
        ArrayList<Long> levels = new ArrayList<>();
        for (long contribution : effective) {
            if (contribution > 0 && !levels.contains(contribution)) levels.add(contribution);
        }
        levels.sort(Long::compare);

        ArrayList<Pot> pots = new ArrayList<>();
        long previous = 0;
        for (long level : levels) {
            long width = Math.subtractExact(level, previous);
            ArrayList<PlayerId> contributors = new ArrayList<>();
            ArrayList<PlayerId> eligible = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                HoldemHand.PlayerState player = players.get(i);
                if (effective.get(i) >= level) {
                    contributors.add(player.id);
                    if (foldWin ? player == foldWinner : !player.folded) eligible.add(player.id);
                }
            }
            long amount = Math.multiplyExact(width, contributors.size());
            if (amount <= 0) throw new IllegalStateException("pot layer amount must be positive");
            if (eligible.isEmpty()) throw new IllegalStateException("pot layer has no eligible player");
            pots.add(new Pot(amount, level, contributors, eligible));
            previous = level;
        }
        return List.copyOf(pots);
    }
}
