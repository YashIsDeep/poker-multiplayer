package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.engine.cards.Card;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HandEventTest {
    @Test
    void initialTransitionContainsOrderedBlindsAndPrivateDealsAfterBothHandsExist() {
        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 20),
                        EngineTestFixtures.player("small", 1, 20),
                        EngineTestFixtures.player("big", 2, 20)),
                0, EngineTestFixtures.orderedDeck());

        HandTransition initial = hand.initialTransitions().get(0);
        assertEquals(List.of(HandEvent.BlindPosted.class, HandEvent.BlindPosted.class,
                        HandEvent.HoleCardDealt.class, HandEvent.HoleCardDealt.class,
                        HandEvent.HoleCardDealt.class, HandEvent.HoleCardDealt.class,
                        HandEvent.HoleCardDealt.class, HandEvent.HoleCardDealt.class),
                initial.events().stream().map(Object::getClass).toList());
        assertTrue(initial.snapshotAfter().players().stream().allMatch(player -> player.holeCards().size() == 2));
    }

    @Test
    void burnCardsHaveNoPublicSnapshotOrEventField() {
        List<String> snapshotFields = Arrays.stream(HandSnapshot.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertFalse(snapshotFields.contains("burns"));
        for (Class<?> eventType : HandEvent.class.getPermittedSubclasses()) {
            assertFalse(Arrays.stream(eventType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .anyMatch(name -> name.toLowerCase().contains("burn")));
        }

        HoldemHand hand = HoldemHand.start(new HandConfig(1, 2, 9),
                List.of(EngineTestFixtures.player("button", 0, 20),
                        EngineTestFixtures.player("big", 1, 20)),
                0, EngineTestFixtures.orderedDeck());
        hand.act(new PlayerId("button"), new PlayerAction.Call());
        ActionResult flop = hand.act(new PlayerId("big"), new PlayerAction.Check());
        HandEvent.StreetDealt event = flop.transitions().stream()
                .flatMap(transition -> transition.events().stream())
                .filter(HandEvent.StreetDealt.class::isInstance)
                .map(HandEvent.StreetDealt.class::cast)
                .findFirst().orElseThrow();
        assertFalse(event.cardsAdded().contains(new Card(4)));
        assertFalse(event.boardAfter().contains(new Card(4)));
    }
}
