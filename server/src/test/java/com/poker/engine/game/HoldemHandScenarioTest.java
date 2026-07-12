package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.engine.cards.Card;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class HoldemHandScenarioTest {
    private static final HandConfig CONFIG = new HandConfig(10, 20, 9);

    @Test
    void headsUpUsesButtonAsSmallBlindAndActsFirstPreflop() {
        var deck = EngineTestFixtures.orderedDeck();
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100), EngineTestFixtures.player("big", 1, 100)),
                0, deck);

        HandSnapshot start = hand.snapshot();
        assertEquals(0, start.smallBlindSeat());
        assertEquals(1, start.bigBlindSeat());
        assertEquals(OptionalInt.of(0), start.actingSeat());
        assertEquals(0, deck.remaining());
        assertEquals(1, hand.initialTransitions().get(0).sequence());

        hand.act(new PlayerId("button"), new PlayerAction.Call());
        assertEquals(OptionalInt.of(1), hand.snapshot().actingSeat());
        hand.act(new PlayerId("big"), new PlayerAction.Check());
        assertEquals(HandPhase.FLOP, hand.snapshot().phase());
        assertEquals(OptionalInt.of(1), hand.snapshot().actingSeat());
    }

    @Test
    void threeWayUsesAscendingLeftSeatOrder() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 100),
                        EngineTestFixtures.player("big", 2, 100)),
                0, EngineTestFixtures.orderedDeck());

        assertEquals(1, hand.snapshot().smallBlindSeat());
        assertEquals(2, hand.snapshot().bigBlindSeat());
        assertEquals(OptionalInt.of(0), hand.snapshot().actingSeat());
    }

    @Test
    void foldWinRefundsUncalledExcessAndConservesChips() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("a"), new PlayerAction.RaiseTo(40));
        ActionResult result = hand.act(new PlayerId("b"), new PlayerAction.Fold());

        assertEquals(HandPhase.COMPLETE, result.snapshot().phase());
        assertEquals(CompletionReason.FOLD_WIN, result.snapshot().completionReason().orElseThrow());
        assertTrue(result.transitions().stream().flatMap(t -> t.events().stream())
                .anyMatch(event -> event instanceof HandEvent.UncalledBetRefunded refund && refund.amount() == 20));
        assertEquals(120, result.snapshot().players().get(0).stack());
        assertEquals(80, result.snapshot().players().get(1).stack());
        EngineTestFixtures.assertConserved(result.snapshot());
    }

    @Test
    void immediateAllInStartRunsToShowdownWithoutSyntheticAction() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 10), EngineTestFixtures.player("b", 1, 10)),
                0, EngineTestFixtures.orderedDeck());

        HandSnapshot snapshot = hand.snapshot();
        assertEquals(HandPhase.COMPLETE, snapshot.phase());
        assertEquals(5, snapshot.board().size());
        assertTrue(hand.legalActions().isEmpty());
        assertTrue(hand.initialTransitions().size() >= 6);
        EngineTestFixtures.assertConserved(snapshot);
    }

    @Test
    void rejectedActionsAreTypedAtomicAndRaiseToUsesStreetTotal() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, EngineTestFixtures.orderedDeck());
        HandSnapshot before = hand.snapshot();

        ActionRejectedException outOfTurn = assertThrows(ActionRejectedException.class,
                () -> hand.act(new PlayerId("b"), new PlayerAction.Check()));
        assertEquals(ActionRejectionReason.NOT_ACTOR, outOfTurn.reason());
        assertEquals(before, hand.snapshot());

        ActionRejectedException underRaise = assertThrows(ActionRejectedException.class,
                () -> hand.act(new PlayerId("a"), new PlayerAction.RaiseTo(20)));
        assertEquals(ActionRejectionReason.INVALID_RAISE_TO, underRaise.reason());
        assertEquals(before, hand.snapshot());

        hand.act(new PlayerId("a"), new PlayerAction.RaiseTo(40));
        assertEquals(40, hand.snapshot().currentBet());
        assertEquals(40, hand.snapshot().players().get(0).streetCommitted());
    }

    @Test
    void aShortAllInDoesNotReopenAPlayerWhoAlreadyActed() {
        HandConfig config = new HandConfig(10, 20, 9);
        HoldemHand hand = HoldemHand.start(config,
                List.of(EngineTestFixtures.player("a", 0, 100),
                        EngineTestFixtures.player("b", 1, 50),
                        EngineTestFixtures.player("c", 2, 35)),
                0, EngineTestFixtures.orderedDeck());

        // Button calls 20; the big blind calls; the small blind-side seat raises all-in to 35.
        hand.act(new PlayerId("a"), new PlayerAction.Call());
        hand.act(new PlayerId("b"), new PlayerAction.Call());
        hand.act(new PlayerId("c"), new PlayerAction.RaiseTo(35));
        LegalActions a = hand.legalActions().orElseThrow();
        assertEquals(new PlayerId("a"), a.playerId());
        assertFalse(a.canRaise());
        assertTrue(a.canCall());
    }

    @Test
    void cumulativeShortAllInsReopenAnEarlierCallerAfterOneFullIncrease() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 30),
                        EngineTestFixtures.player("big", 2, 40),
                        EngineTestFixtures.player("utg", 3, 100)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("utg"), new PlayerAction.Call());
        hand.act(new PlayerId("button"), new PlayerAction.Call());
        hand.act(new PlayerId("small"), new PlayerAction.RaiseTo(30));
        hand.act(new PlayerId("big"), new PlayerAction.RaiseTo(40));

        LegalActions utg = hand.legalActions().orElseThrow();
        assertEquals(new PlayerId("utg"), utg.playerId());
        assertTrue(utg.canRaise());
        assertEquals(60, utg.minimumRaiseTo());
    }

    @Test
    void threeWaySidePotsUseStackCapsAndFoldedPlayersStayIneligible() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("button", 0, 100),
                        EngineTestFixtures.player("small", 1, 50),
                        EngineTestFixtures.player("big", 2, 35)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(40));
        hand.act(new PlayerId("small"), new PlayerAction.RaiseTo(50));
        hand.act(new PlayerId("big"), new PlayerAction.Call());
        ActionResult result = hand.act(new PlayerId("button"), new PlayerAction.Call());

        HandSnapshot actionFrame = result.transitions().get(0).snapshotAfter();
        assertEquals(List.of(105L, 30L), actionFrame.pots().stream().map(Pot::amount).toList());
        assertEquals(135, result.snapshot().awards().stream().mapToLong(PotAward::potAmount).sum());
        EngineTestFixtures.assertConserved(result.snapshot());
    }

    @Test
    void oddChipStartsWithFirstWinningSeatLeftOfButtonAndSkipsFoldedSeat() {
        List<Card> prefix = List.of(
                Card.parse("9c"), Card.parse("Tc"), Card.parse("Jc"),
                Card.parse("9d"), Card.parse("Td"), Card.parse("Jd"),
                Card.parse("Qc"),
                Card.parse("2c"), Card.parse("3d"), Card.parse("4h"),
                Card.parse("Qd"), Card.parse("5s"),
                Card.parse("Qh"), Card.parse("6c"));
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 10),
                        EngineTestFixtures.player("small", 1, 10),
                        EngineTestFixtures.player("big", 2, 10)),
                0, EngineTestFixtures.deckWithPrefix(prefix));

        hand.act(new PlayerId("button"), new PlayerAction.Call());
        hand.act(new PlayerId("small"), new PlayerAction.Fold());
        hand.act(new PlayerId("big"), new PlayerAction.Check());
        HandSnapshot result = EngineTestFixtures.playChecksAndCalls(hand);

        assertEquals(CompletionReason.SHOWDOWN, result.completionReason().orElseThrow());
        PotAward main = result.awards().get(0);
        assertEquals(List.of(new PlayerId("big"), new PlayerId("button")),
                main.winnerAwards().stream().map(WinnerAward::playerId).toList());
        assertEquals(List.of(2L, 1L), main.winnerAwards().stream().map(WinnerAward::amount).toList());
        EngineTestFixtures.assertConserved(result);
    }

    @Test
    void transitionsAreMonotonicAndFinalFrameMatchesResult() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, EngineTestFixtures.orderedDeck());
        ArrayList<HandTransition> all = new ArrayList<>(hand.initialTransitions());
        while (hand.legalActions().isPresent()) {
            LegalActions legal = hand.legalActions().orElseThrow();
            ActionResult result = hand.act(legal.playerId(), legal.canCall() ? new PlayerAction.Call() : new PlayerAction.Check());
            all.addAll(result.transitions());
            assertEquals(result.snapshot(), result.transitions().get(result.transitions().size() - 1).snapshotAfter());
        }
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i).sequence() > all.get(i - 1).sequence());
        assertNotNull(hand.snapshot().completionReason().orElse(null));
        EngineTestFixtures.assertConserved(hand.snapshot());
    }
}
