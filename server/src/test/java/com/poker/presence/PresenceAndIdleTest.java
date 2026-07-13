package com.poker.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomConfig;
import com.poker.room.RoomManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresenceAndIdleTest {
    private static final Duration TABLE_GRACE = Duration.ofMinutes(10);
    private static final Duration MEMBER_GRACE = Duration.ofMinutes(10);
    private static final Duration INACTIVITY = Duration.ofMinutes(60);

    @Test
    void socketRegistryIsMultiTabDisconnectIdempotentAndSessionAware() {
        UserSocketRegistry registry = new UserSocketRegistry();
        UUID user = UUID.randomUUID();
        assertThat(registry.register(user, "a", "http-1"))
                .isEqualTo(UserSocketRegistry.Transition.ZERO_TO_ONE);
        assertThat(registry.register(user, "b", "http-2"))
                .isEqualTo(UserSocketRegistry.Transition.UNCHANGED);
        assertThat(registry.removeHttpSession("http-1")).isEmpty();
        assertThat(registry.count(user)).isEqualTo(1);
        assertThat(registry.removeHttpSession("http-2")).containsExactly(user);
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
    void confirmedOfflineHidesTableAndReconnectRestoresItWithoutSittingIn() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);

        RoomChange offline = rooms.memberDisconnected(user, now).orElseThrow();
        assertThat(offline.room().member(user).connected()).isFalse();
        assertThat(offline.room().member(user).disconnectedSince()).isEqualTo(now);
        assertThat(rooms.lobby().rooms()).isEmpty();

        RoomChange online = rooms.memberConnected(user, now.plusSeconds(5)).orElseThrow();
        assertThat(online.room().member(user).connected()).isTrue();
        assertThat(online.room().member(user).disconnectedSince()).isNull();
        assertThat(rooms.lobby().rooms()).hasSize(1);
        assertThat(rooms.room(created.roomId())).isPresent();
    }

    @Test
    void guardedAbandonedTableCleanupClosesAndAdvancesEpoch() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);
        long before = rooms.membershipEpoch(user);
        rooms.memberDisconnected(user, now).orElseThrow();
        RoomManager.CleanupIntent intent = intent(rooms, created.roomId());

        RoomChange closed = rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), intent.due(),
                TABLE_GRACE, MEMBER_GRACE, INACTIVITY).change();
        assertThat(closed.kind()).isEqualTo(RoomChange.Kind.IDLE_CLOSED);
        assertThat(rooms.roomCount()).isZero();
        assertThat(rooms.membershipEpoch(user)).isEqualTo(before + 1);
    }

    @Test
    void staleCleanupGenerationCannotAffectReconnect() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);
        rooms.memberDisconnected(user, now).orElseThrow();
        RoomManager.CleanupIntent stale = intent(rooms, created.roomId());
        rooms.memberConnected(user, now.plusSeconds(5)).orElseThrow();

        assertThat(rooms.cleanupDue(
                created.roomId(), stale.generation(), stale.due(), stale.due(),
                TABLE_GRACE, MEMBER_GRACE, INACTIVITY).change()).isNull();
        assertThat(rooms.roomCount()).isEqualTo(1);
    }

    @Test
    void individuallyDisconnectedMemberIsEvictedWhileConnectedTableRemains() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(a, "A", null), "T", 2);
        rooms.join(new MemberProfile(b, "B", null), created.room().joinCodeValue());
        rooms.memberDisconnected(a, now).orElseThrow();
        RoomManager.CleanupIntent intent = intent(rooms, created.roomId());

        RoomChange evicted = rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), intent.due(),
                TABLE_GRACE, MEMBER_GRACE, INACTIVITY).change();
        assertThat(evicted.kind()).isEqualTo(RoomChange.Kind.DISCONNECTED_MEMBER_EVICTED);
        assertThat(evicted.exitedUserId()).isEqualTo(a);
        assertThat(evicted.room().member(a)).isNull();
        assertThat(evicted.room().member(b).connected()).isTrue();
        assertThat(rooms.roomCount()).isEqualTo(1);
    }

    @Test
    void privateTableClosesAfterOneHourOfInactivity() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(
                new MemberProfile(user, "A", null), "T", 2, RoomConfig.DEFAULT, true);
        RoomManager.CleanupIntent intent = intent(rooms, created.roomId());
        assertThat(intent.due()).isEqualTo(now.plus(INACTIVITY));

        assertThat(rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), intent.due().minusMillis(1),
                TABLE_GRACE, MEMBER_GRACE, INACTIVITY).change()).isNull();
        assertThat(rooms.room(created.roomId())).isPresent();

        RoomChange closed = rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), intent.due(),
                TABLE_GRACE, MEMBER_GRACE, INACTIVITY).change();
        assertThat(closed.kind()).isEqualTo(RoomChange.Kind.IDLE_CLOSED);
        assertThat(rooms.room(created.roomId())).isEmpty();
    }

    @Test
    void keepAliveMovesOnlyTheInactivityDeadline() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        MutableClock clock = new MutableClock(now);
        RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator());
        UUID user = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(user, "A", null), "T", 2);
        RoomManager.CleanupIntent before = intent(rooms, created.roomId());
        clock.set(now.plusSeconds(30));

        RoomChange kept = rooms.keepAlive(user, created.roomId());
        RoomManager.CleanupIntent after = intent(rooms, created.roomId());
        assertThat(kept.kind()).isEqualTo(RoomChange.Kind.KEEP_ALIVE);
        assertThat(after.generation()).isGreaterThan(before.generation());
        assertThat(after.due()).isEqualTo(now.plusSeconds(30).plus(INACTIVITY));
    }

    @Test
    void pausedTableReceivesFiveMinuteRecoveryWindowBeforeClose() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        RoomManager rooms = roomsAt(now);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(a, "A", null), "T", 2);
        rooms.join(new MemberProfile(b, "B", null), created.room().joinCodeValue());
        rooms.takeSeat(a, created.roomId(), 0, 500);
        RoomChange waiting = rooms.takeSeat(b, created.roomId(), 1, 500);
        rooms.pauseGameplay(created.roomId(), waiting.room().game().scheduleGeneration()).orElseThrow();
        Duration earlyInactivity = Duration.ofMinutes(1);
        RoomManager.CleanupIntent intent = rooms.cleanupIntent(
                created.roomId(), TABLE_GRACE, MEMBER_GRACE, earlyInactivity).orElseThrow();

        assertThat(rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), intent.due(),
                TABLE_GRACE, MEMBER_GRACE, earlyInactivity).retryAt()).isNotNull();
        assertThat(rooms.cleanupDue(
                created.roomId(), intent.generation(), intent.due(), now.plus(Duration.ofMinutes(5)),
                TABLE_GRACE, MEMBER_GRACE, earlyInactivity).change()).isNotNull();
    }

    private static RoomManager roomsAt(Instant now) {
        return new RoomManager(Clock.fixed(now, ZoneOffset.UTC), new JoinCodeGenerator());
    }

    private static RoomManager.CleanupIntent intent(RoomManager rooms, UUID roomId) {
        return rooms.cleanupIntent(roomId, TABLE_GRACE, MEMBER_GRACE, INACTIVITY).orElseThrow();
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
