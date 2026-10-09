package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.Deck;
import com.poker.engine.game.HandPhase;
import com.poker.room.*;
import com.poker.ws.protocol.GameView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class GameSnapshotRedactorTest {
    private final MutableClock clock = new MutableClock();
    private final RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator(new Random(17)),
            () -> deckWithPrefix("2c", "As", "Kh", "2d", "Ah", "Qh", "3c", "4c", "5d", "7h", "8c", "9s", "Tc", "Jd"));
    private final GameSnapshotRedactor redactor = new GameSnapshotRedactor();

    @ParameterizedTest
    @ValueSource(strings = {"stand", "leave", "queued-stand"})
    void vacatedFoldedSeatDoesNotRevealCardsOrInheritHistory(String departure) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), replacement = UUID.randomUUID();
        RoomChange frame = rooms.create(profile(a), "Private cards", 3, RoomConfig.DEFAULT);
        UUID roomId = frame.roomId();
        rooms.join(profile(b), frame.room().joinCodeValue());
        rooms.join(profile(replacement), frame.room().joinCodeValue());
        rooms.takeSeat(a, roomId, 0, 500);
        frame = start(rooms.takeSeat(b, roomId, 1, 500));
        UUID folded = actor(frame.room().game());
        int vacatedSeat = frame.room().member(folded).seat();
        if (departure.equals("queued-stand")) rooms.stand(folded, roomId);
        frame = complete(rooms.act(folded, roomId, frame.room().game().actionSeq(), GameAction.FOLD, null));
        var historicalHand = frame.room().game().presentedHand();
        if (departure.equals("leave")) rooms.leave(folded, roomId);
        else if (rooms.room(roomId).orElseThrow().member(folded).seat() != null) rooms.stand(folded, roomId);
        frame = rooms.takeSeat(replacement, roomId, vacatedSeat, 1_000);
        assertThat(frame.room().game().presentedHand()).isEqualTo(historicalHand);
        assertThat(historicalHand.phase()).isEqualTo(HandPhase.COMPLETE);
        assertCleanReplacement(frame.room(), replacement, vacatedSeat, 1_000);
        // The original owner must not reveal their former cards on another user's seat either.
        assertThat(seat(redactor.redact(frame.room(), folded), vacatedSeat).holeCards()).isNull();
    }

    @Test
    void bustedPlayerSeatReassignmentDuringShowdownKeepsHistoricalOwnershipSeparate() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), replacement = UUID.randomUUID();
        RoomChange frame = rooms.create(profile(a), "Showdown", 3, RoomConfig.table(10, 200, true, 200, 3), true);
        UUID roomId = frame.roomId();
        rooms.join(profile(b), frame.room().joinCodeValue());
        rooms.join(profile(c), frame.room().joinCodeValue());
        rooms.join(profile(replacement), frame.room().joinCodeValue());
        rooms.takeSeat(a, roomId, 0, 200);
        rooms.takeSeat(b, roomId, 1, 200);
        frame = start(rooms.takeSeat(c, roomId, 2, 200));
        frame = act(frame, GameAction.RAISE_TO, 200L);
        frame = act(frame, GameAction.FOLD, null);
        frame = complete(act(frame, GameAction.CALL, null));
        TableSeatState busted = frame.room().game().seats().stream()
                .filter(s -> s.status() == TableSeatStatus.BUSTED).findFirst().orElseThrow();
        assertThat(seat(redactor.redact(frame.room(), busted.userId()), busted.seat()).holeCards()).hasSize(2);
        var historicalHand = frame.room().game().presentedHand();
        rooms.stand(busted.userId(), roomId);
        frame = rooms.takeSeat(replacement, roomId, busted.seat(), 200);
        assertThat(frame.room().game().presentedHand()).isEqualTo(historicalHand);
        assertCleanReplacement(frame.room(), replacement, busted.seat(), 200);
    }

    private void assertCleanReplacement(RoomState room, UUID replacement, int seat, long balance) {
        for (UUID viewer : Arrays.asList(replacement, null)) {
            GameView.GameSeatView view = seat(redactor.redact(room, viewer), seat);
            assertThat(view.userId()).isEqualTo(replacement);
            assertThat(view.holeCards()).isNull();
            assertThat(view.stack()).isEqualTo(balance);
            assertThat(view.committed()).isZero();
            assertThat(view.seatStatus()).isEqualTo("ACTIVE");
            assertThat(view.actions()).isEmpty();
            assertThat(view.lastAction()).isNull();
            assertThat(view.position()).isNull();
            assertThat(view.finalHandRank()).isNull();
            assertThat(view.bestHandCards()).isEmpty();
        }
    }

    private static GameView.GameSeatView seat(GameView view, int seat) {
        return view.seats().stream().filter(s -> s.seat() == seat).findFirst().orElseThrow();
    }
    private RoomChange start(RoomChange frame) {
        clock.now = clock.now.plusSeconds(2);
        GameState game = frame.room().game();
        return rooms.startHand(frame.roomId(), game.lifecycleSeq(), game.scheduleGeneration()).orElseThrow();
    }
    private RoomChange act(RoomChange frame, GameAction action, Long amount) {
        return rooms.act(actor(frame.room().game()), frame.roomId(), frame.room().game().actionSeq(), action, amount);
    }
    private RoomChange complete(RoomChange frame) {
        while (frame.room().game().presentedHand().phase() != HandPhase.COMPLETE) {
            GameState game = frame.room().game();
            frame = rooms.advancePresentation(frame.roomId(), game.handId(), game.scheduleGeneration()).orElseThrow();
        }
        return frame;
    }
    private static UUID actor(GameState game) {
        int seat = game.presentedHand().actingSeat().orElseThrow();
        return game.seats().stream().filter(s -> s.seat() == seat).findFirst().orElseThrow().userId();
    }
    private static MemberProfile profile(UUID user) { return new MemberProfile(user, user.toString(), null); }
    private static Deck deckWithPrefix(String... prefix) {
        List<Card> cards = new ArrayList<>();
        for (String card : prefix) cards.add(Card.parse(card));
        for (int i = 0; i < 52; i++) if (!cards.contains(new Card(i))) cards.add(new Card(i));
        return Deck.of(cards);
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-13T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
