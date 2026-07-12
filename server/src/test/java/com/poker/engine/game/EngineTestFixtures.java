package com.poker.engine.game;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.Deck;

import java.util.ArrayList;
import java.util.List;

final class EngineTestFixtures {
    private EngineTestFixtures() {}

    static PlayerAtSeat player(String id, int seat, long stack) {
        return new PlayerAtSeat(new PlayerId(id), seat, stack);
    }

    static Deck orderedDeck() {
        ArrayList<Card> cards = new ArrayList<>(52);
        for (int code = 0; code < 52; code++) cards.add(new Card(code));
        return Deck.of(cards);
    }

    static Deck deckWithPrefix(List<Card> prefix) {
        ArrayList<Card> cards = new ArrayList<>(52);
        cards.addAll(prefix);
        for (int code = 0; code < 52; code++) {
            Card card = new Card(code);
            if (!cards.contains(card)) cards.add(card);
        }
        return Deck.of(cards);
    }

    static void assertConserved(HandSnapshot snapshot) {
        long total = 0;
        for (PlayerSnapshot player : snapshot.players()) {
            total = Math.addExact(total, Math.addExact(player.stack(), player.handCommitted()));
        }
        if (snapshot.phase() == HandPhase.COMPLETE) {
            long stacks = snapshot.players().stream().mapToLong(PlayerSnapshot::stack).sum();
            org.junit.jupiter.api.Assertions.assertEquals(snapshot.totalChipsAtStart(), stacks);
        } else {
            org.junit.jupiter.api.Assertions.assertEquals(snapshot.totalChipsAtStart(), total);
        }
    }

    static HandSnapshot playChecksAndCalls(HoldemHand hand) {
        int guard = 0;
        while (hand.legalActions().isPresent()) {
            LegalActions legal = hand.legalActions().orElseThrow();
            PlayerAction action = legal.canCall() ? new PlayerAction.Call() : new PlayerAction.Check();
            hand.act(legal.playerId(), action);
            if (++guard > 100) throw new AssertionError("hand did not terminate");
        }
        return hand.snapshot();
    }
}
