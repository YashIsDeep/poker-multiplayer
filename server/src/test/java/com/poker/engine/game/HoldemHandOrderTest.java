package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class HoldemHandOrderTest {
    @Test
    void sparseSeatsWrapForBlindsDealAndPreflopAction() {
        HoldemHand hand = HoldemHand.start(new HandConfig(10, 20, 9),
                List.of(EngineTestFixtures.player("button", 8, 100),
                        EngineTestFixtures.player("small", 0, 100),
                        EngineTestFixtures.player("big", 4, 100)),
                8, EngineTestFixtures.orderedDeck());

        HandSnapshot snapshot = hand.snapshot();
        assertEquals(0, snapshot.smallBlindSeat());
        assertEquals(4, snapshot.bigBlindSeat());
        assertEquals(OptionalInt.of(8), snapshot.actingSeat());
        assertEquals(List.of(0, 4, 8), snapshot.players().stream().map(PlayerSnapshot::seat).toList());

        List<HandEvent.HoleCardDealt> deals = hand.initialTransitions().get(0).events().stream()
                .filter(HandEvent.HoleCardDealt.class::isInstance)
                .map(HandEvent.HoleCardDealt.class::cast)
                .toList();
        assertEquals(List.of(0, 4, 8, 0, 4, 8), deals.stream().map(HandEvent.HoleCardDealt::seat).toList());
    }
}
