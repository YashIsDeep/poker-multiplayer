package com.poker.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresenceAndIdleTest {
    @Test
    void socketRegistryIsMultiTabAndDisconnectIdempotent() {
        UserSocketRegistry registry = new UserSocketRegistry();
        UUID user = UUID.randomUUID();
        assertThat(registry.register(user, "a"))
                .isEqualTo(UserSocketRegistry.Transition.ZERO_TO_ONE);
        assertThat(registry.register(user, "b"))
                .isEqualTo(UserSocketRegistry.Transition.UNCHANGED);
        assertThat(registry.remove("a").transition())
                .isEqualTo(UserSocketRegistry.Transition.UNCHANGED);
        assertThat(registry.remove("b").transition())
                .isEqualTo(UserSocketRegistry.Transition.ONE_TO_ZERO);
        assertThat(registry.remove("b").transition())
                .isEqualTo(UserSocketRegistry.Transition.UNCHANGED);
    }

    @Test
    void liveSessionCannotChangeIdentity() {
        UserSocketRegistry registry = new UserSocketRegistry();
        registry.register(UUID.randomUUID(), "session");
        assertThatThrownBy(() -> registry.register(UUID.randomUUID(), "session"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void guardedIdleExpiryDeletesSafeRoomAndAdvancesEpoch() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = new RoomManager(
                Clock.fixed(now, ZoneOffset.UTC), new JoinCodeGenerator());
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);
        long before = rooms.membershipEpoch(user);
        RoomManager.IdleGuard guard = rooms.markRoomDisconnected(created.roomId(), now);
        Instant due = now.plus(Duration.ofMinutes(30));

        RoomChange closed = rooms.expireIdleRoom(
                created.roomId(), guard.generation(), due, due, Duration.ofMinutes(30)).orElseThrow();
        assertThat(closed.kind()).isEqualTo(RoomChange.Kind.IDLE_CLOSED);
        assertThat(rooms.roomCount()).isZero();
        assertThat(rooms.membershipEpoch(user)).isEqualTo(before + 1);
    }

    @Test
    void expiryRequiresExactDueTimeAndCurrentGeneration() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        Duration grace = Duration.ofMinutes(30);
        RoomManager rooms = new RoomManager(
                Clock.fixed(now, ZoneOffset.UTC), new JoinCodeGenerator());
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);
        RoomManager.IdleGuard guard = rooms.markRoomDisconnected(created.roomId(), now);

        assertThat(rooms.expireIdleRoom(
                created.roomId(), guard.generation(), now.plus(grace).plusSeconds(1),
                now.plus(grace).plusSeconds(1), grace)).isEmpty();
        rooms.markRoomConnected(created.roomId());
        assertThat(rooms.idleGuardCurrent(
                created.roomId(), guard.generation(), now.plus(grace), grace)).isFalse();
        assertThat(rooms.expireIdleRoom(
                created.roomId(), guard.generation(), now.plus(grace),
                now.plus(grace), grace)).isEmpty();
        assertThat(rooms.roomCount()).isEqualTo(1);
    }

    @Test
    void pausedTableReceivesFiveMinuteRecoveryWindowBeforeIdleClose() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        Duration grace = Duration.ofMinutes(1);
        RoomManager rooms = new RoomManager(
                Clock.fixed(now, ZoneOffset.UTC), new JoinCodeGenerator());
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange created=rooms.create(new MemberProfile(a,"A",null),"T",2);
        rooms.join(new MemberProfile(b,"B",null),created.room().joinCodeValue());
        rooms.takeSeat(a,created.roomId(),0,500);
        RoomChange waiting=rooms.takeSeat(b,created.roomId(),1,500);
        rooms.pauseGameplay(created.roomId(),waiting.room().game().scheduleGeneration()).orElseThrow();
        RoomManager.IdleGuard guard=rooms.markRoomDisconnected(created.roomId(),now);
        Instant due=now.plus(grace);

        assertThat(rooms.expireIdleRoom(
                created.roomId(),guard.generation(),due,due,grace)).isEmpty();
        assertThat(rooms.expireIdleRoom(
                created.roomId(),guard.generation(),due,now.plus(Duration.ofMinutes(5)),grace))
                .isPresent();
    }
}
