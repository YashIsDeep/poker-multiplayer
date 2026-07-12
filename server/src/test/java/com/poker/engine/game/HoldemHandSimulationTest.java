package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.engine.cards.Deck;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HoldemHandSimulationTest {
    @Test
    void fixedSeedTenThousandHandsTerminateAndConserveEveryChip() {
        Random random = new Random(0x5EEDC0DEL);
        HandConfig config = new HandConfig(1, 2, 9);
        for (int handIndex = 0; handIndex < 10_000; handIndex++) {
            int count = 2 + handIndex % 8;
            ArrayList<PlayerAtSeat> players = new ArrayList<>(count);
            for (int seat = 0; seat < count; seat++) {
                players.add(EngineTestFixtures.player("p" + seat, seat, 20 + random.nextInt(181)));
            }
            int button = random.nextInt(count);
            HoldemHand hand = HoldemHand.start(config, players, button, Deck.shuffled(random));
            int actions = 0;
            while (hand.legalActions().isPresent()) {
                LegalActions legal = hand.legalActions().orElseThrow();
                int choice = random.nextInt(100);
                PlayerAction action;
                if (legal.canRaise() && choice < 18) {
                    long amount = legal.minimumRaiseTo();
                    if (legal.maximumRaiseTo() > amount && random.nextBoolean()) amount = legal.maximumRaiseTo();
                    action = new PlayerAction.RaiseTo(amount);
                } else if (legal.canCall() && choice < 78) {
                    action = new PlayerAction.Call();
                } else if (legal.canCheck()) {
                    action = new PlayerAction.Check();
                } else {
                    action = new PlayerAction.Fold();
                }
                ActionResult result = hand.act(legal.playerId(), action);
                EngineTestFixtures.assertConserved(result.snapshot());
                if (++actions > 500) throw new AssertionError("simulation hand did not terminate: " + handIndex);
            }
            HandSnapshot complete = hand.snapshot();
            assertEquals(HandPhase.COMPLETE, complete.phase(), "hand " + handIndex);
            assertTrue(complete.actingSeat().isEmpty());
            EngineTestFixtures.assertConserved(complete);
        }
    }
}
