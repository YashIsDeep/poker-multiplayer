package com.poker.room;

import static org.assertj.core.api.Assertions.*;
import com.poker.room.application.RankedSettlement;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankedConsolidationTest {
    final RankedGameplayTest f = new RankedGameplayTest();
    final RoomManager rooms = f.rooms;
    record Pair(UUID target, UUID source, List<UUID> moved) {}

    Pair twoSmallTables(RankedSettlement settlement) {
        RoomState target = f.table(settlement), source = null;
        List<UUID> extras = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (i == 3) f.clock.now = f.clock.now.plusSeconds(1);
            UUID user = UUID.randomUUID(); extras.add(user);
            source = rooms.assignRanked(new MemberProfile(user, "Extra", null), settlement).room();
        }
        for (int i = 0; i < 3; i++) rooms.leave(extras.get(i), target.roomId());
        return new Pair(target.roomId(), source.roomId(), extras.subList(3, 6));
    }
    RoomState state(UUID id) { return rooms.room(id).orElseThrow(); }

    @Test void combinesThreeAndThreeWithSingleMembershipAndInvalidatesOldRoom() {
        var pair = twoSmallTables(f.settlement);
        String oldCode = state(pair.source).joinCodeValue();
        long epoch = rooms.membershipEpoch(pair.moved.getFirst());
        rooms.consolidateRanked();
        assertThat(rooms.roomCount()).isEqualTo(1);
        assertThat(state(pair.target).memberCount()).isEqualTo(6);
        assertThat(state(pair.target).game().seats()).hasSize(6);
        assertThat(state(pair.target).game().nextHandAt()).isNotNull();
        assertThat(rooms.membershipEpoch(pair.moved.getFirst())).isGreaterThan(epoch);
        for (UUID user : pair.moved) assertThat(rooms.state(user).room().roomId()).isEqualTo(pair.target);
        assertThat(rooms.consolidateRanked()).isEmpty();
        assertThatThrownBy(() -> rooms.join(new MemberProfile(UUID.randomUUID(), "Other", null), oldCode)).isInstanceOf(RoomException.class);
        assertThat(rooms.startHand(pair.source, 1, 1)).isEmpty();
    }

    @Test void waitsForBothCompletedSettlementsAndHoldsTheEarlierTable() {
        var failedRoom = new AtomicReference<UUID>();
        var pair = twoSmallTables((id, hand) -> {
            if (id.equals(failedRoom.get())) throw new IllegalStateException("database unavailable");
            f.settled.add(hand);
        });
        f.start(state(pair.target)); f.start(state(pair.source));
        failedRoom.set(pair.source);
        rooms.consolidateRanked();
        assertThat(state(pair.target).game().consolidationHeld()).isTrue();
        assertThat(rooms.hasRankedVacancy()).isFalse();
        assertThat(state(pair.target).game().legalActions()).isNotNull();
        f.boundary(f.finish(state(pair.target), false));
        f.finish(state(pair.source), false);
        assertThat(state(pair.source).game().tableStatus()).isEqualTo(TableStatus.SETTLING);
        rooms.consolidateRanked();
        assertThat(rooms.roomCount()).isEqualTo(2);
        assertThat(state(pair.target).game().nextHandAt()).isNull();
        failedRoom.set(null);
        var pending = state(pair.source).game();
        rooms.advancePresentation(pair.source, pending.handId(), pending.scheduleGeneration());
        f.boundary(state(pair.source));
        Map<UUID, TablePlayerState> before = new HashMap<>(state(pair.target).game().players());
        before.putAll(state(pair.source).game().players());
        rooms.consolidateRanked();
        assertThat(state(pair.target).game().players()).containsAllEntriesOf(before);
        assertThat(f.settled).hasSize(2);
        assertThat(f.start(state(pair.target)).game().handId()).isEqualTo(2);
    }

    @Test void carriesStacksRebuyCountsWaitsAndStandingPlayersWithoutAddingEarnings() {
        var pair = twoSmallTables(f.settlement);
        var source = f.boundary(f.finish(f.start(state(pair.source)), true));
        UUID rebought = source.game().rebuyOfferUserId();
        rooms.rebuy(rebought, pair.source, 1000);
        rooms.stand(rebought, pair.source);
        Map<UUID, TablePlayerState> before = state(pair.source).game().players();
        rooms.consolidateRanked();
        var merged = state(pair.target);
        for (UUID user : pair.moved) {
            var expected = before.get(user); var actual = merged.game().players().get(user);
            assertThat(actual.stack()).isEqualTo(expected.stack());
            assertThat(actual.rebuyHandsRemaining()).isEqualTo(expected.rebuyHandsRemaining());
            assertThat(actual.rebuyCount()).isEqualTo(expected.rebuyCount());
        }
        assertThat(merged.member(rebought).seat()).isNull();
        assertThat(merged.game().players().get(rebought).rebuyCount()).isEqualTo(1);
        assertThat(f.settled).hasSize(1);
        assertThat(merged.members().stream().mapToLong(m -> merged.game().players().get(m.userId()).stack()).sum()).isEqualTo(7000);
    }

    @Test void respectsReservedCapacityAndLeavesCasualTablesAlone() {
        RoomState first = f.table(f.settlement), second = null;
        List<UUID> extras = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID user = UUID.randomUUID(); extras.add(user);
            second = rooms.assignRanked(new MemberProfile(user, "Extra", null), f.settlement).room();
        }
        rooms.leave(extras.get(0), first.roomId()); rooms.leave(extras.get(1), first.roomId());
        rooms.sitOut(f.users.get(0), first.roomId()); rooms.sitOut(f.users.get(1), first.roomId());
        rooms.create(new MemberProfile(UUID.randomUUID(), "Casual", null), "Casual", 6);
        assertThat(rooms.consolidateRanked()).isEmpty();
        assertThat(state(first.roomId()).memberCount()).isEqualTo(4);
        assertThat(state(second.roomId()).memberCount()).isEqualTo(3);
        assertThat(rooms.roomCount()).isEqualTo(3);
    }

    @Test void oneAndOneCombineButStillWaitForAThirdPlayer() {
        var pair = twoSmallTables(f.settlement);
        for (int i = 1; i < 3; i++) {
            rooms.leave(f.users.get(i), pair.target);
            rooms.leave(pair.moved.get(i), pair.source);
        }
        rooms.consolidateRanked();
        assertThat(state(pair.target).memberCount()).isEqualTo(2);
        assertThat(state(pair.target).game().nextHandAt()).isNull();
    }

    @Test void cancelsReservationIfReturningPlayersMakeATableTooLarge() {
        var pair = twoSmallTables(f.settlement);
        rooms.leave(pair.moved.getLast(), pair.source);
        UUID returning = UUID.randomUUID();
        rooms.assignRanked(new MemberProfile(returning, "Returning", null), f.settlement);
        rooms.sitOut(returning, pair.target);
        f.start(state(pair.target));
        rooms.consolidateRanked();
        assertThat(state(pair.source).game().consolidationHeld()).isTrue();
        rooms.sitIn(returning, pair.target);
        rooms.consolidateRanked();
        assertThat(rooms.roomCount()).isEqualTo(2);
        assertThat(state(pair.target).game().consolidationHeld()).isFalse();
        assertThat(state(pair.source).game().consolidationHeld()).isFalse();
        assertThat(state(pair.target).game().legalActions()).isNotNull();
        assertThat(rooms.hasRankedVacancy()).isTrue();
    }
}
