package com.poker.presence;

import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.application.RoomEventSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** Coordinates generation-guarded idle expiry without holding room locks. */
@Component
public final class RoomIdleCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RoomIdleCoordinator.class);
    private static final Duration MID_HAND_RETRY = Duration.ofSeconds(5);

    private final RoomManager rooms;
    private final UserSocketRegistry sockets;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final RoomEventSink events;
    private final Duration grace;
    private final ConcurrentHashMap<UUID, ScheduledExpiry> tasks = new ConcurrentHashMap<>();

    public RoomIdleCoordinator(
            RoomManager rooms,
            UserSocketRegistry sockets,
            @Qualifier("gameplayTaskScheduler") TaskScheduler scheduler,
            Clock clock,
            RoomEventSink events,
            RoomIdleProperties properties) {
        this.rooms = rooms;
        this.sockets = sockets;
        this.scheduler = scheduler;
        this.clock = clock;
        this.events = events;
        this.grace = properties.getDisconnectedGrace();
    }

    public void reconcile(UUID roomId) {
        Set<UUID> members = rooms.memberIds(roomId);
        if (members.isEmpty()) {
            cancel(roomId);
            return;
        }
        if (!allDisconnected(members)) {
            rooms.markRoomConnected(roomId);
            cancel(roomId);
            return;
        }

        RoomManager.IdleGuard guard = rooms.markRoomDisconnected(roomId, clock.instant());
        Instant due = guard.since().plus(grace);
        schedule(roomId, guard.generation(), due, due, false);
    }

    private void expire(UUID roomId, long generation, Instant due) {
        ScheduledExpiry current = tasks.get(roomId);
        if (current == null || !current.matches(generation, due)) return;

        Set<UUID> members = rooms.memberIds(roomId);
        if (members.isEmpty()) {
            removeCurrent(roomId, generation, due);
            return;
        }
        // Check presence before the room mutation. A socket registered before
        // this point wins; a socket registered after deletion observes a
        // missing room and cannot resurrect stale membership.
        if (!allDisconnected(members)) {
            rooms.markRoomConnected(roomId);
            removeCurrent(roomId, generation, due);
            return;
        }
        if (!rooms.idleGuardCurrent(roomId, generation, due, grace)) {
            removeCurrent(roomId, generation, due);
            reconcile(roomId);
            return;
        }

        var closed = rooms.expireIdleRoom(roomId, generation, due, clock.instant(), grace);
        if (closed.isPresent()) {
            removeCurrent(roomId, generation, due);
            publishCommittedClose(closed.get());
            return;
        }

        // A healthy hand may defer an otherwise-due expiry. Recheck with the
        // same guard; only the latest generation may install the retry.
        if (rooms.idleGuardCurrent(roomId, generation, due, grace)) {
            schedule(roomId, generation, due, clock.instant().plus(MID_HAND_RETRY), true);
        } else {
            removeCurrent(roomId, generation, due);
        }
    }

    private void schedule(
            UUID roomId,
            long generation,
            Instant due,
            Instant runAt,
            boolean replaceMatchingTask) {
        ScheduledFuture<?> future = scheduleWithRetry(
                () -> expire(roomId, generation, due), runAt, roomId);
        if (future == null) return;

        ScheduledExpiry candidate = new ScheduledExpiry(generation, due, future);
        tasks.compute(roomId, (ignored, existing) -> {
            if (existing != null && existing.generation() > generation) {
                future.cancel(false);
                return existing;
            }
            if (!replaceMatchingTask && existing != null && existing.matches(generation, due)
                    && !existing.future().isDone()) {
                future.cancel(false);
                return existing;
            }
            if (existing != null) existing.future().cancel(false);
            return candidate;
        });
    }

    private ScheduledFuture<?> scheduleWithRetry(Runnable task, Instant runAt, UUID roomId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ScheduledFuture<?> future = scheduler.schedule(task, runAt);
                if (future != null) return future;
            } catch (RuntimeException ex) {
                if (attempt == 2) {
                    log.error("Idle expiry scheduling failed roomId={}", roomId, ex);
                }
            }
        }
        return null;
    }

    private boolean allDisconnected(Set<UUID> members) {
        return members.stream().allMatch(userId -> sockets.count(userId) == 0);
    }

    private void publishCommittedClose(RoomChange change) {
        try {
            events.publish(change);
        } catch (RuntimeException ex) {
            log.warn("Idle close publication failed roomId={}", change.roomId(), ex);
        }
    }

    private void removeCurrent(UUID roomId, long generation, Instant due) {
        tasks.computeIfPresent(roomId, (ignored, existing) -> {
            if (!existing.matches(generation, due)) return existing;
            existing.future().cancel(false);
            return null;
        });
    }

    private void cancel(UUID roomId) {
        ScheduledExpiry old = tasks.remove(roomId);
        if (old != null) old.future().cancel(false);
    }

    private record ScheduledExpiry(long generation, Instant due, ScheduledFuture<?> future) {
        private boolean matches(long expectedGeneration, Instant expectedDue) {
            return generation == expectedGeneration && due.equals(expectedDue);
        }
    }
}
