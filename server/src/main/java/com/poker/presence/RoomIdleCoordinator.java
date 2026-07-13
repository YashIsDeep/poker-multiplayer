package com.poker.presence;

import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.application.RoomEventSink;
import com.poker.room.application.ScheduledGameplayCoordinator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Debounces socket loss and reconciles all durable room cleanup deadlines. */
@Component
public final class RoomIdleCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RoomIdleCoordinator.class);

    private final RoomManager rooms;
    private final UserSocketRegistry sockets;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final RoomEventSink events;
    private final ScheduledGameplayCoordinator gameplay;
    private final Duration reconnectGrace;
    private final Duration disconnectedGrace;
    private final Duration memberDisconnectedGrace;
    private final Duration inactivityTimeout;
    private final ConcurrentHashMap<UUID, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledCleanup> cleanupTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> userGenerations = new ConcurrentHashMap<>();

    public RoomIdleCoordinator(
            RoomManager rooms,
            UserSocketRegistry sockets,
            @Qualifier("gameplayTaskScheduler") TaskScheduler scheduler,
            Clock clock,
            RoomEventSink events,
            ScheduledGameplayCoordinator gameplay,
            PresenceProperties presence,
            RoomIdleProperties properties) {
        this.rooms = rooms;
        this.sockets = sockets;
        this.scheduler = scheduler;
        this.clock = clock;
        this.events = events;
        this.gameplay = gameplay;
        this.reconnectGrace = presence.getReconnectGrace();
        this.disconnectedGrace = properties.getDisconnectedGrace();
        this.memberDisconnectedGrace = properties.getMemberDisconnectedGrace();
        this.inactivityTimeout = properties.getInactivityTimeout();
    }

    /** Called only for the zero-to-one socket transition. */
    public void connected(UUID userId) {
        nextUserGeneration(userId);
        cancelPendingDisconnect(userId);
        publish(rooms.memberConnected(userId, clock.instant()).orElse(null));
        rooms.currentRoom(userId).ifPresent(this::reconcile);
    }

    /** Called only after the final socket closes; logical presence stays online during grace. */
    public void disconnected(UUID userId) {
        if (sockets.count(userId) != 0) return;
        long generation = nextUserGeneration(userId);
        Instant due = clock.instant().plus(reconnectGrace);
        ScheduledFuture<?> future = schedule(
                () -> confirmDisconnect(userId, generation, due), due,
                "soft disconnect", userId);
        PendingDisconnect candidate = new PendingDisconnect(generation, due, future);
        pendingDisconnects.compute(userId, (ignored, existing) -> {
            if (existing != null) existing.cancel();
            return candidate;
        });
    }

    /** Explicit logout/session invalidation bypasses reconnect grace. */
    public void hardDisconnected(UUID userId) {
        long generation = nextUserGeneration(userId);
        cancelPendingDisconnect(userId);
        confirmDisconnectedNow(userId, generation);
    }

    public void reconcile(RoomChange change) {
        if (change != null && change.roomId() != null) reconcile(change.roomId());
    }

    public void reconcile(UUID roomId) {
        var intent = rooms.cleanupIntent(
                roomId, disconnectedGrace, memberDisconnectedGrace, inactivityTimeout);
        if (intent.isEmpty()) {
            cancelCleanup(roomId);
            return;
        }
        RoomManager.CleanupIntent value = intent.get();
        scheduleCleanup(roomId, value.generation(), value.due(), value.due(), false);
    }

    /** Recovers rejected schedules and callbacks missed during executor disruption. */
    @Scheduled(fixedDelayString = "${poker.rooms.cleanup-sweep-ms:60000}")
    public void sweep() {
        Instant now = clock.instant();
        for (var entry : pendingDisconnects.entrySet()) {
            PendingDisconnect pending = entry.getValue();
            if (!now.isBefore(pending.due())) {
                confirmDisconnect(entry.getKey(), pending.generation(), pending.due());
            }
        }
        for (UUID roomId : rooms.roomIds()) {
            var intent = rooms.cleanupIntent(
                    roomId, disconnectedGrace, memberDisconnectedGrace, inactivityTimeout);
            if (intent.isPresent() && !now.isBefore(intent.get().due())) {
                cancelCleanup(roomId);
                applyCleanup(roomId, intent.get().generation(), intent.get().due());
            } else {
                reconcile(roomId);
            }
        }
    }

    private void confirmDisconnect(UUID userId, long generation, Instant due) {
        PendingDisconnect pending = pendingDisconnects.get(userId);
        if (pending == null || !pending.matches(generation, due)
                || currentUserGeneration(userId) != generation || sockets.count(userId) != 0) {
            return;
        }
        if (!pendingDisconnects.remove(userId, pending)) return;
        pending.cancel();
        confirmDisconnectedNow(userId, generation);
    }

    private void confirmDisconnectedNow(UUID userId, long generation) {
        if (currentUserGeneration(userId) != generation || sockets.count(userId) != 0) return;
        RoomChange change = rooms.memberDisconnected(userId, clock.instant()).orElse(null);
        publish(change);
        if (change != null && change.roomId() != null) reconcile(change.roomId());
    }

    private void expireCleanup(UUID roomId, long generation, Instant due) {
        ScheduledCleanup current = cleanupTasks.get(roomId);
        if (current == null || !current.matches(generation, due)) return;
        applyCleanup(roomId, generation, due);
    }

    private void applyCleanup(UUID roomId, long generation, Instant due) {
        RoomManager.CleanupOutcome outcome = rooms.cleanupDue(
                roomId, generation, due, clock.instant(),
                disconnectedGrace, memberDisconnectedGrace, inactivityTimeout);
        removeCleanup(roomId, generation, due);
        publish(outcome.change());

        if (outcome.retryAt() != null) {
            scheduleCleanup(roomId, generation, due, outcome.retryAt(), true);
        } else if (rooms.room(roomId).isPresent()) {
            reconcile(roomId);
        }
    }

    private void scheduleCleanup(
            UUID roomId, long generation, Instant due, Instant runAt, boolean replaceMatching) {
        ScheduledFuture<?> future = schedule(
                () -> expireCleanup(roomId, generation, due), runAt, "room cleanup", roomId);
        if (future == null) return;
        ScheduledCleanup candidate = new ScheduledCleanup(generation, due, future);
        cleanupTasks.compute(roomId, (ignored, existing) -> {
            if (existing != null && existing.generation() > generation) {
                future.cancel(false);
                return existing;
            }
            if (!replaceMatching && existing != null && existing.matches(generation, due)
                    && !existing.future().isDone()) {
                future.cancel(false);
                return existing;
            }
            if (existing != null) existing.future().cancel(false);
            return candidate;
        });
    }

    private ScheduledFuture<?> schedule(Runnable task, Instant at, String label, UUID id) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ScheduledFuture<?> future = scheduler.schedule(task, at);
                if (future != null) return future;
            } catch (RuntimeException ex) {
                if (attempt == 2) log.error("{} scheduling failed id={}", label, id, ex);
            }
        }
        return null;
    }

    private void publish(RoomChange change) {
        if (change == null) return;
        try {
            events.publish(change);
        } catch (RuntimeException ex) {
            log.warn("Lifecycle publication failed kind={} roomId={}", change.kind(), change.roomId(), ex);
        }
        try {
            gameplay.reconcile(change);
        } catch (RuntimeException ex) {
            log.error("Lifecycle gameplay reconciliation failed kind={} roomId={}",
                    change.kind(), change.roomId(), ex);
        }
    }

    private long nextUserGeneration(UUID userId) {
        return userGenerations.merge(userId, 1L, (oldValue, one) -> Math.incrementExact(oldValue));
    }

    private long currentUserGeneration(UUID userId) {
        return userGenerations.getOrDefault(userId, 0L);
    }

    private void cancelPendingDisconnect(UUID userId) {
        PendingDisconnect old = pendingDisconnects.remove(userId);
        if (old != null) old.cancel();
    }

    private void cancelCleanup(UUID roomId) {
        ScheduledCleanup old = cleanupTasks.remove(roomId);
        if (old != null) old.future().cancel(false);
    }

    private void removeCleanup(UUID roomId, long generation, Instant due) {
        cleanupTasks.computeIfPresent(roomId, (ignored, existing) -> {
            if (!existing.matches(generation, due)) return existing;
            existing.future().cancel(false);
            return null;
        });
    }

    private record PendingDisconnect(long generation, Instant due, ScheduledFuture<?> future) {
        boolean matches(long expectedGeneration, Instant expectedDue) {
            return generation == expectedGeneration && due.equals(expectedDue);
        }
        void cancel() { if (future != null) future.cancel(false); }
    }

    private record ScheduledCleanup(long generation, Instant due, ScheduledFuture<?> future) {
        boolean matches(long expectedGeneration, Instant expectedDue) {
            return generation == expectedGeneration && due.equals(expectedDue);
        }
    }
}
