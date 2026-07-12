package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PotSettlementTest {
    @Test
    void activeViewSeparatesAUniqueUnmatchedLeaderFromMatchedPots() {
        List<HoldemHand.PlayerState> players = List.of(
                state("short", 0, 50, false),
                state("middle", 1, 100, false),
                state("deep", 2, 200, false));

        PotSettlement.View view = PotSettlement.activeView(players);

        assertEquals(List.of(150L, 100L), view.pots().stream().map(Pot::amount).toList());
        assertEquals(new PendingUnmatched(new PlayerId("deep"), 2, 100), view.pending().orElseThrow());
    }

    @Test
    void contributionLayersIncludeFoldedMoneyButExcludeFoldedEligibility() {
        List<HoldemHand.PlayerState> players = List.of(
                state("a", 0, 25, false),
                state("folded", 1, 75, true),
                state("c", 2, 200, false),
                state("d", 3, 200, false));

        List<Pot> pots = PotSettlement.settlementPots(players, false, null);

        assertEquals(List.of(100L, 150L, 250L), pots.stream().map(Pot::amount).toList());
        assertTrue(pots.get(0).contributors().contains(new PlayerId("folded")));
        assertTrue(pots.stream().noneMatch(pot -> pot.eligible().contains(new PlayerId("folded"))));
    }

    private static HoldemHand.PlayerState state(String id, int seat, long committed, boolean folded) {
        HoldemHand.PlayerState state = new HoldemHand.PlayerState(new PlayerId(id), seat, 0);
        state.handCommitted = committed;
        state.folded = folded;
        return state;
    }
}
