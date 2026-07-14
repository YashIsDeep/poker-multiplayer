package com.poker.room.application;

import com.poker.engine.game.HandPhase;
import com.poker.engine.game.PlayerSnapshot;
import com.poker.room.GameState;
import com.poker.room.RoomChange;
import com.poker.room.RoomManager;
import com.poker.room.TableStatus;
import com.poker.engine.game.CompletionReason;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Generation-guarded scheduler; cancellation is only an optimization. */
@Component
public final class ScheduledGameplayCoordinator {
    private static final long NORMAL_COMPLETE_DISPLAY_SECONDS = 3;
    private static final long SHOWDOWN_DISPLAY_SECONDS = 6;

    private final RoomManager rooms;
    private final RoomEventSink events;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    public ScheduledGameplayCoordinator(
            RoomManager rooms,
            RoomEventSink events,
            @Qualifier("gameplayTaskScheduler") TaskScheduler scheduler,
            Clock clock) {
        this.rooms = rooms;
        this.events = events;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public void reconcile(RoomChange change) {
        if (change.room() == null) {
            cancel(change.roomId());
            return;
        }
        schedule(change.roomId(), change.room().game(), false);
    }

    @Scheduled(fixedDelayString = "${poker.gameplay.recovery-sweep-ms:5000}")
    public void recoverPausedTables() {
        rooms.pausedRoomIds().stream().limit(100).forEach(roomId -> {
            rooms.room(roomId).ifPresent(state -> rooms.recoverGameplay(
                    roomId, state.game().scheduleGeneration()).ifPresent(this::publishAndReconcile));
        });
    }

    private void schedule(UUID roomId, GameState game, boolean replaceMatchingTask) {
        long generation = game.scheduleGeneration();
        ScheduleIntent intent = intent(roomId, game, generation);
        if (intent == null) {
            cancel(roomId);
            return;
        }

        ScheduledFuture<?> next = scheduleWithRetry(intent.callback(), intent.due());
        if (next == null) {
            rooms.pauseGameplay(roomId, generation).ifPresent(this::publishAndReconcile);
            return;
        }
        tasks.compute(roomId, (ignored, current) -> {
            if (current != null && (current.generation() > generation
                    || (!replaceMatchingTask && current.generation() == generation
                            && !current.future().isDone()))) {
                next.cancel(false);
                return current;
            }
            if (current != null) current.future().cancel(false);
            return new ScheduledTask(generation, next);
        });
    }

    private ScheduleIntent intent(UUID roomId, GameState game, long generation) {
        if (game.tableStatus() == TableStatus.PAUSED || game.tableStatus() == TableStatus.FAILED) {
            return null;
        }
        if (game.nextHandAt() != null && game.presentedHand() == null) {
            long lifecycleSeq = game.lifecycleSeq();
            return new ScheduleIntent(game.nextHandAt(), () -> runCurrent(
                    roomId, generation,
                    () -> rooms.startHand(roomId, lifecycleSeq, generation)));
        }
        if (game.deadline() != null && game.presentedHand() != null
                && game.presentedHand().actingSeat().isPresent()) {
            int actorSeat = game.presentedHand().actingSeat().getAsInt();
            UUID actor = game.presentedHand().players().stream()
                    .filter(player -> player.seat() == actorSeat)
                    .map(PlayerSnapshot::id)
                    .map(Object::toString)
                    .map(UUID::fromString)
                    .findFirst()
                    .orElse(null);
            if (actor == null) return null;
            long handId = game.handId();
            long actionSeq = game.actionSeq();
            return new ScheduleIntent(game.deadline(), () -> runCurrent(
                    roomId, generation,
                    () -> rooms.timeout(roomId, handId, actionSeq, actor)));
        }
        if (game.presentedHand() != null && !game.safeBoundary()
                && game.presentedHand().phase() != HandPhase.COMPLETE) {
            long handId = game.handId();
            return new ScheduleIntent(clock.instant().plusMillis(750), () -> runCurrent(
                    roomId, generation,
                    () -> rooms.advancePresentation(roomId, handId, generation)));
        }
        if (game.presentedHand() != null && game.presentedHand().phase() == HandPhase.COMPLETE) {
            long handId = game.handId();
            long displaySeconds = game.presentedHand().completionReason()
                    .filter(reason -> reason == CompletionReason.SHOWDOWN)
                    .map(ignored -> SHOWDOWN_DISPLAY_SECONDS)
                    .orElse(NORMAL_COMPLETE_DISPLAY_SECONDS);
            return new ScheduleIntent(clock.instant().plusSeconds(displaySeconds), () -> runCurrent(
                    roomId, generation,
                    () -> rooms.nextHand(roomId, handId, generation)));
        }
        if (game.rebuyOfferUserId() != null && game.rebuyOfferExpiresAt() != null) {
            UUID offeredUserId = game.rebuyOfferUserId();
            return new ScheduleIntent(game.rebuyOfferExpiresAt(), () -> runCurrent(
                    roomId, generation,
                    () -> rooms.rotateEmergencyRebuyOffer(roomId, generation, offeredUserId)));
        }
        return null;
    }

    private void runCurrent(
            UUID roomId,
            long generation,
            Supplier<Optional<RoomChange>> callback) {
        ScheduledTask current = tasks.get(roomId);
        if (current == null || current.generation() != generation) return;
        Optional<RoomChange> change = callback.get();
        if (change.isPresent()) {
            publishAndReconcile(change.get());
            return;
        }
        // An early callback (for example after a test/manual clock moves
        // backwards) must reinstall the same guarded intent. A stale callback
        // cannot reach here because its generation no longer owns the slot.
        rooms.room(roomId).ifPresentOrElse(
                state -> schedule(roomId, state.game(), true),
                () -> cancel(roomId));
    }

    private ScheduledFuture<?> scheduleWithRetry(Runnable callback, Instant due) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ScheduledFuture<?> future = scheduler.schedule(callback, due);
                if (future != null) return future;
            } catch (RuntimeException ignored) {
                // Retry once; the caller surfaces a persistent rejection.
            }
        }
        return null;
    }

    private void publishAndReconcile(RoomChange change) {
        try {
            events.publish(change);
        } finally {
            reconcile(change);
        }
    }

    private void cancel(UUID roomId) {
        if (roomId == null) return;
        ScheduledTask old = tasks.remove(roomId);
        if (old != null) old.future().cancel(false);
    }

    private record ScheduleIntent(Instant due, Runnable callback) {}
    private record ScheduledTask(long generation, ScheduledFuture<?> future) {}
}
