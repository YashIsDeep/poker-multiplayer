package com.poker.ranked;

import com.poker.engine.cards.*;
import com.poker.engine.game.*;
import com.poker.room.HandHistory;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

public final class RankedFixtures {
    public static final List<UUID> USERS = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));

    public static HandHistory hand(long id, String style, long... stacks) {
        List<PlayerAtSeat> players = IntStream.range(0, stacks.length)
                .mapToObj(i -> new PlayerAtSeat(PlayerId.of(USERS.get(i).toString()), i, stacks[i])).toList();
        List<Card> cards = new ArrayList<>();
        if (style.equals("split")) {
            for (String code : "2c 3c 4c 5c 6c 7c 8c Ts Js Qs 9c Ks 2d As".split(" ")) cards.add(Card.parse(code));
        }
        IntStream.range(0, 52).mapToObj(Card::new).filter(c -> !cards.contains(c)).forEach(cards::add);
        HoldemHand game = HoldemHand.start(new HandConfig(5, 10, 6), players, 0, Deck.of(cards));
        for (int guard = 0; game.legalActions().isPresent(); guard++) {
            if (guard > 100) throw new AssertionError("hand did not terminate");
            var legal = game.legalActions().orElseThrow();
            PlayerAction action = style.equals("fold") ? new PlayerAction.Fold()
                    : style.equals("allin") && legal.canRaise() ? new PlayerAction.RaiseTo(legal.maximumRaiseTo())
                    : legal.canCheck() ? new PlayerAction.Check() : new PlayerAction.Call();
            game.act(legal.playerId(), action);
        }
        return new HandHistory(id, Instant.EPOCH, Instant.EPOCH.plusSeconds(10), 5, 10,
                players.stream().map(p -> new HandHistory.Player(UUID.fromString(p.id().value()), p.seat(), p.stack())).toList(),
                List.of(), game.snapshot());
    }
}
