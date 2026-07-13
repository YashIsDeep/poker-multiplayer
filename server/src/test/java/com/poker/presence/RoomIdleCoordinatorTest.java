package com.poker.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomManager;
import com.poker.room.application.RoomEventSink;
import com.poker.room.application.ScheduledGameplayCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class RoomIdleCoordinatorTest {
    @Test
    void reconnectWithinSoftGraceProducesNoPresenceMutation() {
        Fixture fixture = new Fixture();
        fixture.sockets.register(fixture.user, "socket-1");
        fixture.sockets.remove("socket-1");
        fixture.coordinator.disconnected(fixture.user);
        long revisionDuringGrace = fixture.rooms.room(fixture.roomId).orElseThrow().revision();

        fixture.sockets.register(fixture.user, "socket-2");
        fixture.coordinator.connected(fixture.user);
        fixture.callbacks.getFirst().run(); // stale soft-disconnect callback

        var member = fixture.rooms.room(fixture.roomId).orElseThrow().member(fixture.user);
        assertThat(member.connected()).isTrue();
        assertThat(member.disconnectedSince()).isNull();
        assertThat(fixture.rooms.room(fixture.roomId).orElseThrow().revision())
                .isEqualTo(revisionDuringGrace);
    }

    @Test
    void finalSocketCloseBecomesOfflineOnlyAfterGrace() {
        Fixture fixture = new Fixture();
        fixture.sockets.register(fixture.user, "socket-1");
        fixture.sockets.remove("socket-1");
        fixture.coordinator.disconnected(fixture.user);
        assertThat(fixture.rooms.room(fixture.roomId).orElseThrow().member(fixture.user).connected())
                .isTrue();

        fixture.clock.set(fixture.now.plusSeconds(10));
        fixture.callbacks.getFirst().run();

        var member = fixture.rooms.room(fixture.roomId).orElseThrow().member(fixture.user);
        assertThat(member.connected()).isFalse();
        assertThat(member.disconnectedSince()).isEqualTo(fixture.now.plusSeconds(10));
        assertThat(fixture.rooms.lobby().rooms()).isEmpty();
    }

    @Test
    void explicitInvalidationBypassesSoftGrace() {
        Fixture fixture = new Fixture();
        fixture.sockets.register(fixture.user, "socket-1", "http-1");
        fixture.sockets.removeHttpSession("http-1");

        fixture.coordinator.hardDisconnected(fixture.user);

        assertThat(fixture.rooms.room(fixture.roomId).orElseThrow().member(fixture.user).connected())
                .isFalse();
    }

    @Test
    void reconciliationSweepExecutesAnOverdueMissedCleanup() {
        Fixture fixture = new Fixture();
        fixture.coordinator.reconcile(fixture.roomId);
        fixture.clock.set(fixture.now.plusSeconds(3600));

        fixture.coordinator.sweep();

        assertThat(fixture.rooms.room(fixture.roomId)).isEmpty();
    }

    private static final class Fixture {
        final Instant now = Instant.parse("2026-07-13T00:00:00Z");
        final MutableClock clock = new MutableClock(now);
        final RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator());
        final UserSocketRegistry sockets = new UserSocketRegistry();
        final List<Runnable> callbacks = new ArrayList<>();
        final UUID user = UUID.randomUUID();
        final UUID roomId;
        final RoomIdleCoordinator coordinator;

        @SuppressWarnings("unchecked")
        Fixture() {
            roomId = rooms.create(new MemberProfile(user, "A", null), "T", 2).roomId();
            TaskScheduler scheduler = mock(TaskScheduler.class);
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
                callbacks.add(invocation.getArgument(0));
                return mock(ScheduledFuture.class);
            });
            PresenceProperties presence = new PresenceProperties();
            RoomIdleProperties cleanup = new RoomIdleProperties();
            coordinator = new RoomIdleCoordinator(
                    rooms, sockets, scheduler, clock,
                    mock(RoomEventSink.class), mock(ScheduledGameplayCoordinator.class),
                    presence, cleanup);
        }
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
