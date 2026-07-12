package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.engine.cards.Card;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldemHandProgressionTest {
    @Test
    void boardProgressionBurnsOneCardPerStreetAndResetsStreetCommitments() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 20),
                        EngineTestFixtures.player("small", 1, 20),
                        EngineTestFixtures.player("big", 2, 20)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.Call());
        hand.act(new PlayerId("small"), new PlayerAction.Call());
        ActionResult flopResult = hand.act(new PlayerId("big"), new PlayerAction.Check());

        assertEquals(HandPhase.FLOP, flopResult.snapshot().phase());
        assertEquals(List.of(new Card(7), new Card(8), new Card(9)), flopResult.snapshot().board());
        assertTrue(flopResult.snapshot().players().stream().allMatch(player -> player.streetCommitted() == 0));
        assertTrue(flopResult.snapshot().players().stream().allMatch(player -> player.handCommitted() == 2));

        playStreetWithChecks(hand);
        assertEquals(List.of(new Card(7), new Card(8), new Card(9), new Card(11)), hand.snapshot().board());
        playStreetWithChecks(hand);
        assertEquals(List.of(new Card(7), new Card(8), new Card(9), new Card(11), new Card(13)), hand.snapshot().board());
    }

    @Test
    void allInRunoutEmitsOneAuthoritativeFramePerRemainingStreet() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 4),
                        EngineTestFixtures.player("big", 1, 4)),
                0, EngineTestFixtures.orderedDeck());

        hand.act(new PlayerId("button"), new PlayerAction.RaiseTo(4));
        ActionResult result = hand.act(new PlayerId("big"), new PlayerAction.Call());

        List<HandPhase> dealtPhases = result.transitions().stream()
                .flatMap(transition -> transition.events().stream())
                .filter(HandEvent.StreetDealt.class::isInstance)
                .map(HandEvent.StreetDealt.class::cast)
                .map(HandEvent.StreetDealt::phase)
                .toList();
        assertEquals(List.of(HandPhase.FLOP, HandPhase.TURN, HandPhase.RIVER), dealtPhases);
        assertEquals(HandPhase.COMPLETE, result.snapshot().phase());
        assertEquals(result.snapshot(), result.transitions().get(result.transitions().size() - 1).snapshotAfter());
    }

    private static void playStreetWithChecks(HoldemHand hand) {
        HandPhase startingPhase = hand.snapshot().phase();
        while (hand.snapshot().phase() == startingPhase) {
            LegalActions legal = hand.legalActions().orElseThrow();
            hand.act(legal.playerId(), new PlayerAction.Check());
        }
    }
}
