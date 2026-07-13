package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.Deck;
import com.poker.engine.game.HandPhase;
import com.poker.room.*;
import com.poker.ws.protocol.HandHistoryView;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HandHistoryMapperTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
    private final RoomManager rooms = new RoomManager(
            clock,
            new JoinCodeGenerator(new java.util.Random(41)),
            () -> Deck.of(IntStream.range(0, 52).mapToObj(Card::new).toList()));

    @Test
    void recordsDetailedActionsAndKeepsFoldedCardsPrivate() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RoomChange frame = rooms.create(profile(alice, "Alice"), "History table", 2, RoomConfig.DEFAULT);
        rooms.join(profile(bob, "Bob"), frame.room().joinCodeValue());
        rooms.takeSeat(alice, frame.roomId(), 0, 500);
        frame = rooms.takeSeat(bob, frame.roomId(), 1, 500);

        clock.advance(Duration.ofSeconds(2));
        GameState waiting = frame.room().game();
        frame = rooms.startHand(
                frame.roomId(), waiting.lifecycleSeq(), waiting.scheduleGeneration()).orElseThrow();

        List<HandHistoryView> live = new HandHistoryMapper().forViewer(frame.room(), alice);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().events())
                .extracting(HandHistoryView.EventView::type)
                .containsExactly("BLIND_POSTED", "BLIND_POSTED");
        assertThat(live.getFirst().players().stream()
                .filter(player -> player.userId().equals(alice)).findFirst().orElseThrow().holeCards())
                .hasSize(2);
        assertThat(live.getFirst().players().stream()
                .filter(player -> player.userId().equals(bob)).findFirst().orElseThrow().holeCards())
                .isNull();

        GameState game = frame.room().game();
        int actorSeat = game.presentedHand().actingSeat().orElseThrow();
        UUID actor = game.seats().stream()
                .filter(seat -> seat.seat() == actorSeat).findFirst().orElseThrow().userId();
        frame = rooms.act(actor, frame.roomId(), game.actionSeq(), GameAction.FOLD, null);
        while (frame.room().game().presentedHand().phase() != HandPhase.COMPLETE) {
            game = frame.room().game();
            frame = rooms.advancePresentation(
                    frame.roomId(), game.handId(), game.scheduleGeneration()).orElseThrow();
        }

        HandHistoryView completed = new HandHistoryMapper().forViewer(frame.room(), alice).getFirst();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.completionReason()).isEqualTo("FOLD_WIN");
        assertThat(completed.events())
                .extracting(HandHistoryView.EventView::type)
                .contains("PLAYER_ACTED", "POT_AWARDED", "HAND_COMPLETED");
        assertThat(completed.players().stream()
                .filter(player -> player.userId().equals(bob)).findFirst().orElseThrow().holeCards())
                .isNull();
        assertThat(completed.players()).extracting(HandHistoryView.PlayerView::netChange)
                .anyMatch(change -> change > 0)
                .anyMatch(change -> change < 0);
    }

    private static MemberProfile profile(UUID id, String name) {
        return new MemberProfile(id, name, null);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
