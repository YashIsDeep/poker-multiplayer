package com.poker.room;

import static org.assertj.core.api.Assertions.*;
import com.poker.engine.cards.Deck;
import com.poker.engine.game.HandPhase;
import com.poker.room.application.RankedSettlement;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RankedGameplayTest {
    final MutableClock clock = new MutableClock();
    final RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator(new Random(4)), () -> Deck.shuffled(new Random(19)));
    final List<HandHistory> settled = new ArrayList<>();
    final List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    final RankedSettlement settlement = (room, hand) -> settled.add(hand);

    RoomState table(RankedSettlement sink) {
        RoomChange change = null;
        for (var user : users) change = rooms.assignRanked(new MemberProfile(user, "Player", null), sink);
        return change.room();
    }
    RoomState start(RoomState room) {
        var game = room.game(); clock.now = game.nextHandAt();
        return rooms.startHand(room.roomId(), game.lifecycleSeq(), game.scheduleGeneration()).orElseThrow().room();
    }
    RoomState finish(RoomState room, boolean allin) {
        for (int guard = 0; guard < 100; guard++) {
            var game = room.game();
            if (game.tableStatus() == TableStatus.SETTLING || game.safeBoundary()) return room;
            if (game.legalActions() != null) {
                var legal = game.legalActions();
                GameAction action = allin ? legal.canRaise() ? GameAction.RAISE_TO : legal.canCall() ? GameAction.CALL : GameAction.CHECK : GameAction.FOLD;
                room = rooms.act(UUID.fromString(legal.playerId().value()), room.roomId(), game.actionSeq(), action,
                        action == GameAction.RAISE_TO ? legal.maximumRaiseTo() : null).room();
            } else room = rooms.advancePresentation(room.roomId(), game.handId(), game.scheduleGeneration()).orElseThrow().room();
        }
        throw new AssertionError("hand never completed");
    }
    RoomState boundary(RoomState room) {
        return rooms.nextHand(room.roomId(), room.game().handId(), room.game().scheduleGeneration()).orElseThrow().room();
    }

    @Test void fixedRulesThreePlayerMinimumAndNoDirectEntryOrSeatSelection() {
        var first = rooms.assignRanked(new MemberProfile(users.get(0), "A", null), settlement);
        var second = rooms.assignRanked(new MemberProfile(users.get(1), "B", null), settlement);
        assertThat(second.room().game().nextHandAt()).isNull();
        assertThat(second.roomId()).isEqualTo(first.roomId());
        var room = table(settlement);
        assertThat(room.config()).isEqualTo(RoomConfig.table(10, 1000, true, 1000, 30, 5));
        assertThat(room.game().nextHandAt()).isNotNull();
        assertThatThrownBy(() -> rooms.join(new MemberProfile(UUID.randomUUID(), "Intruder", null), room.joinCodeValue())).isInstanceOf(RoomException.class);
        assertThatThrownBy(() -> rooms.takeSeat(users.getFirst(), room.roomId(), 0, 1000)).isInstanceOf(RoomException.class);
        assertThatThrownBy(() -> rooms.updateSettings(users.getFirst(), room.roomId(), RoomConfig.DEFAULT)).isInstanceOf(RoomException.class);
        assertThat(rooms.assignRanked(new MemberProfile(users.getFirst(), "A", null), settlement).room().memberCount()).isEqualTo(3);
    }

    @Test void settlementFailureHidesResultBlocksNextHandAndRetriesWithoutDuplicatingHistory() {
        AtomicBoolean fail = new AtomicBoolean(true);
        var room = finish(start(table((id, hand) -> {
            if (fail.get()) throw new IllegalStateException("database unavailable");
            settled.add(hand);
        })), false);
        assertThat(room.game().tableStatus()).isEqualTo(TableStatus.SETTLING);
        assertThat(rooms.lobby().rooms()).singleElement().satisfies(lobby -> {
            assertThat(lobby.mode()).isEqualTo("RANKED");
            assertThat(lobby.rankedStatus()).isEqualTo("SETTLING");
        });
        assertThat(room.game().presentedHand().phase()).isNotEqualTo(HandPhase.COMPLETE);
        assertThat(room.game().handHistories()).noneMatch(h -> h.completedAt() != null);
        assertThat(room.game().deadline()).isNull();
        assertThat(rooms.nextHand(room.roomId(), room.game().handId(), room.game().scheduleGeneration())).isEmpty();
        assertThat(settled).isEmpty();
        fail.set(false);
        room = rooms.advancePresentation(room.roomId(), room.game().handId(), room.game().scheduleGeneration()).orElseThrow().room();
        assertThat(room.game().presentedHand().phase()).isEqualTo(HandPhase.COMPLETE);
        assertThat(settled).hasSize(1);
        assertThat(room.game().handHistories()).hasSize(1);
        assertThat(rooms.advancePresentation(room.roomId(), room.game().handId(), room.game().scheduleGeneration())).isEmpty();
        assertThat(boundary(room).game().nextHandAt()).isNotNull();
    }

    @Test void departuresAndTimeoutsCountThenTableWaitsBelowThree() {
        var room = start(table(settlement));
        UUID actor = UUID.fromString(room.game().legalActions().playerId().value());
        clock.now = room.game().deadline();
        room = rooms.timeout(room.roomId(), room.game().handId(), room.game().actionSeq(), actor).orElseThrow().room();
        rooms.stand(actor, room.roomId());
        room = finish(room, false);
        assertThat(settled.getFirst().players()).hasSize(3).anyMatch(p -> p.userId().equals(actor));
        room = boundary(room);
        rooms.leave(actor, room.roomId());
        assertThat(rooms.room(room.roomId()).orElseThrow().game().nextHandAt()).isNull();
    }

    @Test void emergencyManualRebuysRestoreThreePlayersWithoutResettingOtherStacks() {
        var room = boundary(finish(start(table(settlement)), true));
        assertThat(settled).hasSize(1);
        assertThat(room.game().nextHandAt()).isNull();
        assertThat(room.game().rebuyOfferUserId()).isNotNull();
        UUID firstOffer = room.game().rebuyOfferUserId();
        clock.now = room.game().rebuyOfferExpiresAt();
        room = rooms.rotateEmergencyRebuyOffer(room.roomId(), room.game().scheduleGeneration(), firstOffer).orElseThrow().room();
        assertThat(room.game().rebuyOfferUserId()).isNotEqualTo(firstOffer);
        for (int i = 0; i < 2; i++) {
            UUID offered = room.game().rebuyOfferUserId();
            assertThat(offered).isNotNull();
            room = rooms.rebuy(offered, room.roomId(), 1000).room();
            assertThat(room.member(offered).seat()).isNotNull();
        }
        assertThat(room.game().nextHandAt()).isNotNull();
        assertThat(room.game().seats().stream().mapToLong(TableSeatState::stack).sum()).isEqualTo(5000);
        assertThat(settled).hasSize(1);
    }

    @Test void casualPlayDoesNotCallRankedSettlement() {
        var created = rooms.create(new MemberProfile(users.getFirst(), "A", null), "Casual", 3);
        for (int i = 1; i < 3; i++) rooms.join(new MemberProfile(users.get(i), "P", null), created.room().joinCodeValue());
        RoomState room = null;
        for (int i = 0; i < 3; i++) room = rooms.takeSeat(users.get(i), created.roomId(), i, 1000).room();
        assertThat(finish(start(room), false).game().ranked()).isFalse();
        assertThat(settled).isEmpty();
    }

    @Test void normalRebuyRequiresFiveCompletedHandsWhenEnoughPlayersRemain() {
        RoomState room = table(settlement);
        for (int i = 0; i < 3; i++) room = rooms.assignRanked(new MemberProfile(UUID.randomUUID(), "Extra", null), settlement).room();
        room = start(room);
        int actions = 0;
        while (!room.game().safeBoundary()) {
            var game = room.game();
            var legal = game.legalActions();
            if (legal != null) {
                GameAction action = actions == 0 ? GameAction.RAISE_TO : actions == 1 ? GameAction.CALL : GameAction.FOLD;
                room = rooms.act(UUID.fromString(legal.playerId().value()), room.roomId(), game.actionSeq(), action,
                        action == GameAction.RAISE_TO ? legal.maximumRaiseTo() : null).room();
                actions++;
            } else room = rooms.advancePresentation(room.roomId(), game.handId(), game.scheduleGeneration()).orElseThrow().room();
        }
        room = boundary(room);
        UUID busted = room.game().players().entrySet().stream().filter(e -> e.getValue().stack() == 0).findFirst().orElseThrow().getKey();
        UUID roomId = room.roomId();
        assertThat(room.game().rebuyOfferUserId()).isNull();
        assertThat(room.game().players().get(busted).rebuyHandsRemaining()).isEqualTo(5);
        assertThatThrownBy(() -> rooms.rebuy(busted, roomId, 1000)).isInstanceOf(GameException.class);
        // Sitting out between hands can also create a shortage. The offer must
        // open immediately, then close when enough funded players return.
        var temporarilyOut = room.game().seats().stream().limit(3).map(TableSeatState::userId).toList();
        for (UUID user : temporarilyOut) room = rooms.sitOut(user, roomId).room();
        assertThat(room.game().nextHandAt()).isNull();
        assertThat(room.game().rebuyOfferUserId()).isEqualTo(busted);
        for (UUID user : temporarilyOut) room = rooms.sitIn(user, roomId).room();
        assertThat(room.game().rebuyOfferUserId()).isNull();
        for (int i = 0; i < 5; i++) room = boundary(finish(start(room), false));
        assertThat(room.game().players().get(busted).rebuyHandsRemaining()).isZero();
        room = rooms.rebuy(busted, roomId, 1000).room();
        assertThat(room.member(busted).seat()).isNotNull();
        assertThat(room.game().players().get(busted).stack()).isEqualTo(1000);
        assertThat(settled).hasSize(6);
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
