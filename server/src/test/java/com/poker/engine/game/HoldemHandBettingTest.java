package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldemHandBettingTest {
    private static final HandConfig CONFIG = new HandConfig(10, 20, 9);

    @Test
    void twoShortBlindsDoNotLowerTheNominalBringIn() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 5),
                        EngineTestFixtures.player("big", 2, 7)),
                0, EngineTestFixtures.orderedDeck());

        LegalActions legal = hand.legalActions().orElseThrow();
        assertEquals(new PlayerId("button"), legal.playerId());
        assertEquals(20, legal.toCall());
        assertEquals(20, legal.callAmount());
        assertEquals(40, legal.minimumRaiseTo());
    }

    @Test
    void aFullRaiseUsesTheActualRaiseSizeForTheNextMinimum() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 200),
                        EngineTestFixtures.player("small", 1, 200),
                        EngineTestFixtures.player("big", 2, 200)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(50));
        LegalActions small = hand.legalActions().orElseThrow();
        assertEquals(80, small.minimumRaiseTo());
        hand.act(new PlayerId("small"), new PlayerAction.RaiseTo(80));
        assertEquals(110, hand.legalActions().orElseThrow().minimumRaiseTo());
    }

    @Test
    void aNonAllInUnderRaiseIsRejectedWithoutMutation() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("big", 1, 100)),
                0, EngineTestFixtures.orderedDeck());
        HandSnapshot before = hand.snapshot();

        ActionRejectedException exception = assertThrows(ActionRejectedException.class,
                () -> hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(30)));

        assertEquals(ActionRejectionReason.INVALID_RAISE_TO, exception.reason());
        assertEquals(before, hand.snapshot());
    }

    @Test
    void postflopOpeningBetUsesTheNominalBigBlind() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("big", 1, 100)),
                0, EngineTestFixtures.orderedDeck());
        hand.act(new PlayerId("button"), new PlayerAction.Call());
        hand.act(new PlayerId("big"), new PlayerAction.Check());

        LegalActions postflop = hand.legalActions().orElseThrow();
        assertTrue(postflop.canCheck());
        assertFalse(postflop.canCall());
        assertEquals(20, postflop.minimumRaiseTo());
    }
}
