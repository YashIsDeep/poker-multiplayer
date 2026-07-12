package com.poker.room.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.TableStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class ScheduledGameplayCoordinatorTest {
    @Test
    void persistentSchedulerRejectionPublishesPausedTable() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);
        RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator());
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(alice, "Alice", null), "T", 2);
        rooms.join(new MemberProfile(bob, "Bob", null), created.room().joinCodeValue());
        rooms.takeSeat(alice, created.roomId(), 0, 500);
        RoomChange waiting = rooms.takeSeat(bob, created.roomId(), 1, 500);

        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(null);
        RoomEventSink sink = mock(RoomEventSink.class);
        ScheduledGameplayCoordinator coordinator = new ScheduledGameplayCoordinator(
                rooms, sink, scheduler, clock);

        coordinator.reconcile(waiting);

        assertThat(rooms.room(created.roomId()).orElseThrow().game().tableStatus())
                .isEqualTo(TableStatus.PAUSED);
        verify(sink).publish(any(RoomChange.class));
    }

    @Test
    void recoverySweepResumesWhenSchedulerAcceptsWorkAgain() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);
        RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator());
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RoomChange created = rooms.create(new MemberProfile(alice, "Alice", null), "T", 2);
        rooms.join(new MemberProfile(bob, "Bob", null), created.room().joinCodeValue());
        rooms.takeSeat(alice, created.roomId(), 0, 500);
        RoomChange waiting = rooms.takeSeat(bob, created.roomId(), 1, 500);

        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> accepted = mock(ScheduledFuture.class);
        AtomicInteger attempts = new AtomicInteger();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(ignored -> attempts.getAndIncrement() < 2 ? null : accepted);
        RoomEventSink sink = mock(RoomEventSink.class);
        ScheduledGameplayCoordinator coordinator = new ScheduledGameplayCoordinator(
                rooms, sink, scheduler, clock);

        coordinator.reconcile(waiting);
        assertThat(rooms.room(created.roomId()).orElseThrow().game().tableStatus())
                .isEqualTo(TableStatus.PAUSED);
        coordinator.recoverPausedTables();

        assertThat(rooms.room(created.roomId()).orElseThrow().game().tableStatus())
                .isEqualTo(TableStatus.WAITING);
        verify(sink, atLeast(2)).publish(any(RoomChange.class));
    }
}
