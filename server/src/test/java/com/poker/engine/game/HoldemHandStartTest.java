package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldemHandStartTest {
    private static final HandConfig CONFIG = new HandConfig(10, 20, 9);

    @Test
    void failedStartLeavesDeckCursorUnchanged() {
        var deck = EngineTestFixtures.orderedDeck();
        List<PlayerAtSeat> duplicateSeats = List.of(
                EngineTestFixtures.player("a", 0, 100),
                EngineTestFixtures.player("b", 0, 100));

        assertThrows(IllegalArgumentException.class, () -> HoldemHand.start(CONFIG, duplicateSeats, 0, deck));
        assertEquals(52, deck.remaining());

        deck.draw();
        assertThrows(IllegalArgumentException.class, () -> HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, deck));
        assertEquals(51, deck.remaining());
    }

    @Test
    void successfulStartConsumesDeckAndDefensivelyCopiesNestedState() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, EngineTestFixtures.orderedDeck());
        HandSnapshot snapshot = hand.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().add(null));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().get(0).holeCards().clear());
        assertThrows(UnsupportedOperationException.class, () -> hand.initialTransitions().clear());
    }

    @Test
    void actionValidationPrecedenceReturnsStableReasons() {
        HoldemHand hand = HoldemHand.start(CONFIG,
                List.of(EngineTestFixtures.player("a", 0, 100), EngineTestFixtures.player("b", 1, 100)),
                0, EngineTestFixtures.orderedDeck());

        ActionRejectedException unknown = assertThrows(ActionRejectedException.class,
                () -> hand.act(new PlayerId("missing"), new PlayerAction.Check()));
        assertEquals(ActionRejectionReason.UNKNOWN_PLAYER, unknown.reason());
        ActionRejectedException illegal = assertThrows(ActionRejectedException.class,
                () -> hand.act(new PlayerId("a"), new PlayerAction.Check()));
        assertEquals(ActionRejectionReason.ACTION_NOT_LEGAL, illegal.reason());
    }
}
