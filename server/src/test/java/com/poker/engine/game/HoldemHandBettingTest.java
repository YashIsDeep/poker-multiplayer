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
    void preflopMinimumRaiseIsToTwiceTheBigBlind() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("big", 1, 100)),
                0, EngineTestFixtures.orderedDeck());

        LegalActions legal = hand.legalActions().orElseThrow();
        assertEquals(1, legal.toCall());
        assertEquals(4, legal.minimumRaiseTo());
    }

    @Test
    void preflopRaiseSizeExcludesThePostedBigBlind() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 100),
                        EngineTestFixtures.player("big", 2, 100)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(10));

        assertEquals(18, hand.legalActions().orElseThrow().minimumRaiseTo());
    }

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

    @Test
    void eachStreetResetsBeforeAnOpeningBetSetsTheNextMinimum() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 100),
                        EngineTestFixtures.player("big", 2, 100)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(10));
        hand.act(new PlayerId("small"), new PlayerAction.Call());
        hand.act(new PlayerId("big"), new PlayerAction.Call());

        LegalActions flop = hand.legalActions().orElseThrow();
        assertEquals(new PlayerId("small"), flop.playerId());
        assertEquals(2, flop.minimumRaiseTo());

        hand.act(new PlayerId("small"), new PlayerAction.RaiseTo(10));
        assertEquals(20, hand.legalActions().orElseThrow().minimumRaiseTo());
    }

    @Test
    void shortAllInRaiseIsAllowedButDoesNotReopenTheOriginalRaiser() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("raiser", 0, 200),
                        EngineTestFixtures.player("short", 1, 50),
                        EngineTestFixtures.player("caller", 2, 200)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("raiser"), new PlayerAction.RaiseTo(40));
        LegalActions shortStack = hand.legalActions().orElseThrow();
        assertEquals(50, shortStack.minimumRaiseTo());
        assertEquals(50, shortStack.maximumRaiseTo());
        hand.act(new PlayerId("short"), new PlayerAction.RaiseTo(50));
        hand.act(new PlayerId("caller"), new PlayerAction.Call());

        LegalActions originalRaiser = hand.legalActions().orElseThrow();
        assertEquals(new PlayerId("raiser"), originalRaiser.playerId());
        assertTrue(originalRaiser.canCall());
        assertFalse(originalRaiser.canRaise());
    }

    @Test
    void fullAllInRaiseReopensActionAndSetsTheNextMinimum() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("raiser", 0, 200),
                        EngineTestFixtures.player("all-in", 1, 100),
                        EngineTestFixtures.player("caller", 2, 200)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("raiser"), new PlayerAction.RaiseTo(40));
        hand.act(new PlayerId("all-in"), new PlayerAction.RaiseTo(100));
        hand.act(new PlayerId("caller"), new PlayerAction.Call());

        LegalActions originalRaiser = hand.legalActions().orElseThrow();
        assertTrue(originalRaiser.canRaise());
        assertEquals(160, originalRaiser.minimumRaiseTo());
        assertEquals(200, originalRaiser.maximumRaiseTo());
    }

    @Test
    void postflopShortAllInCanBeCompletedToTheBigBlind() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("short", 1, 3),
                        EngineTestFixtures.player("big", 2, 100)),
                0, EngineTestFixtures.orderedDeck());
        hand.act(new PlayerId("button"), new PlayerAction.Call());
        hand.act(new PlayerId("short"), new PlayerAction.Call());
        hand.act(new PlayerId("big"), new PlayerAction.Check());

        LegalActions shortStack = hand.legalActions().orElseThrow();
        assertEquals(1, shortStack.minimumRaiseTo());
        assertEquals(1, shortStack.maximumRaiseTo());
        hand.act(new PlayerId("short"), new PlayerAction.RaiseTo(1));

        LegalActions completion = hand.legalActions().orElseThrow();
        assertEquals(2, completion.minimumRaiseTo());
        hand.act(new PlayerId("big"), new PlayerAction.RaiseTo(10));
        assertEquals(20, hand.legalActions().orElseThrow().minimumRaiseTo());
    }
}
