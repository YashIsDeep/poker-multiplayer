package com.poker.room;

import com.poker.engine.game.*;
import com.poker.room.application.DeckFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Room-lock-confined adapter around the pure HoldemHand engine. */
final class TableGame {
    private static final long INTER_HAND_DELAY_SECONDS = 2;
    private final RoomConfig config;
    private final int maxSeats;
    private final Clock clock;
    private final DeckFactory decks;
    private final Map<Integer, TableSeatState> seats = new HashMap<>();
    private final Map<Integer, HandEvent.PlayerActed> lastActions = new HashMap<>();
    private final ArrayDeque<HandTransition> presentation = new ArrayDeque<>();
    private HoldemHand hand;
    private HandSnapshot presented;
    private TableStatus status = TableStatus.WAITING;
    private long handId;
    private long actionSeq;
    private long lifecycleSeq = 1;
    private long scheduleGeneration = 1;
    private Instant deadline;
    private Instant nextHandAt;
    private Instant pausedDeadline;
    private Instant pausedNextHandAt;
    private Instant pausedSince;
    private Integer lastButton;

    TableGame(RoomConfig config, int maxSeats, Clock clock, DeckFactory decks) {
        this.config = config; this.maxSeats = maxSeats; this.clock = clock; this.decks = decks;
    }

    void seat(int seat, UUID userId, long buyIn) {
        config.validateBuyIn(buyIn);
        TableSeatState existing = seats.get(seat);
        if (existing != null) {
            if (existing.userId().equals(userId) && existing.initialBuyIn() == buyIn) return;
            throw new RoomException(RoomError.ALREADY_SEATED);
        }
        long previousLifecycle = lifecycleSeq;
        seats.put(seat, new TableSeatState(
                seat, userId, buyIn, buyIn, TableSeatStatus.ACTIVE, false, false, null));
        try {
            lifecycle();
            scheduleStartIfReady();
        } catch (RuntimeException ex) {
            seats.remove(seat);
            lifecycleSeq = previousLifecycle;
            throw ex;
        }
    }

    boolean matchesSeatRequest(int seat, UUID userId, long buyIn) {
        TableSeatState state = seats.get(seat);
        return state != null && state.userId().equals(userId) && state.initialBuyIn() == buyIn;
    }

    boolean stand(int seat) {
        TableSeatState state = required(seat);
        if (hand != null && playerInHand(seat)) {
            seats.put(seat, copy(state, state.status(), state.sitOutAfterHand(), true, state.pendingRebuy()));
            lifecycle();
            return false;
        }
        seats.remove(seat);
        lifecycle();
        scheduleStartIfReady();
        return true;
    }

    void removeAtBoundary(int seat) {
        seats.remove(seat);
        lifecycle();
        scheduleStartIfReady();
    }
    boolean handIncludes(int seat) { return hand != null && playerInHand(seat); }
    boolean standAfterHand(int seat) { return required(seat).standAfterHand(); }

    void sitOut(int seat) {
        TableSeatState state = required(seat);
        if (state.status() == TableSeatStatus.SITTING_OUT || state.sitOutAfterHand()) throw new GameException(GameError.ALREADY_SITTING_OUT);
        if (hand != null && playerInHand(seat)) seats.put(seat, copy(state, state.status(), true, state.standAfterHand(), state.pendingRebuy()));
        else seats.put(seat, copy(state, TableSeatStatus.SITTING_OUT, false, state.standAfterHand(), state.pendingRebuy()));
        lifecycle();
        if (hand == null) scheduleStartIfReady();
    }

    /** Presence-driven sit-out. Repeated reconciliation is intentionally a no-op. */
    boolean autoSitOut(int seat) {
        TableSeatState state = required(seat);
        if (state.status() == TableSeatStatus.SITTING_OUT || state.sitOutAfterHand()) return false;
        if (hand != null && playerInHand(seat)) {
            seats.put(seat, copy(
                    state, state.status(), true, state.standAfterHand(), state.pendingRebuy()));
        } else {
            seats.put(seat, copy(
                    state, TableSeatStatus.SITTING_OUT, false,
                    state.standAfterHand(), state.pendingRebuy()));
        }
        lifecycle();
        if (hand == null) scheduleStartIfReady();
        return true;
    }

    /** Cleanup-driven stand. Returns true only when the seat was removed immediately. */
    boolean autoStand(int seat) {
        TableSeatState state = required(seat);
        if (hand != null && playerInHand(seat)) {
            if (state.standAfterHand()) return false;
            seats.put(seat, copy(
                    state, state.status(), state.sitOutAfterHand(), true, state.pendingRebuy()));
            lifecycle();
            return false;
        }
        seats.remove(seat);
        lifecycle();
        scheduleStartIfReady();
        return true;
    }

    void sitIn(int seat) {
        TableSeatState state = required(seat);
        if (state.status() != TableSeatStatus.SITTING_OUT && !state.sitOutAfterHand()) throw new GameException(GameError.ALREADY_SITTING_IN);
        seats.put(seat, copy(state, state.stack() == 0 ? TableSeatStatus.BUSTED : TableSeatStatus.ACTIVE, false, state.standAfterHand(), state.pendingRebuy()));
        lifecycle();
        scheduleStartIfReady();
    }

    void rebuy(int seat, long amount) {
        config.validateBuyIn(amount);
        TableSeatState state = required(seat);
        if (state.stack() > 0 || (hand != null && playerInHand(seat))) throw new GameException(GameError.REBUY_NOT_AVAILABLE);
        seats.put(seat, copy(state, state.status(), state.sitOutAfterHand(), state.standAfterHand(), amount));
        lifecycle();
        if (hand == null) applyBoundary();
    }

    void act(UUID userId, long expectedSeq, GameAction action, Long amount) {
        if (expectedSeq != actionSeq) throw new GameException(GameError.STALE_ACTION);
        if (status != TableStatus.RUNNING || hand == null || presented == null
                || presented.phase() == HandPhase.COMPLETE) {
            throw new GameException(GameError.HAND_NOT_ACTIVE);
        }
        if (action == null || (action != GameAction.RAISE_TO && amount != null)) {
            throw new GameException(GameError.ACTION_NOT_LEGAL);
        }
        int seat = seatFor(userId);
        if (presented.actingSeat().isEmpty() || presented.actingSeat().getAsInt() != seat) throw new GameException(GameError.NOT_YOUR_TURN);
        PlayerAction engineAction = switch (action) {
            case FOLD -> new PlayerAction.Fold();
            case CHECK -> new PlayerAction.Check();
            case CALL -> new PlayerAction.Call();
            case RAISE_TO -> {
                if (amount == null || amount <= 0) throw new GameException(GameError.INVALID_RAISE_TO);
                yield new PlayerAction.RaiseTo(amount);
            }
        };
        ActionResult result;
        try { result = hand.act(PlayerId.of(userId.toString()), engineAction); }
        catch (ActionRejectedException ex) {
            throw new GameException(ex.reason() == ActionRejectionReason.INVALID_RAISE_TO
                    ? GameError.INVALID_RAISE_TO : GameError.ACTION_NOT_LEGAL, ex);
        }
        presentation.clear();
        presentation.addAll(result.transitions());
        exposeNextTransition();
    }

    boolean timeout(long expectedHand, long expectedSeq, UUID expectedActor) {
        if (handId != expectedHand || actionSeq != expectedSeq || deadline == null || clock.instant().isBefore(deadline)) return false;
        LegalActions legal = hand.legalActions().orElse(null);
        if (legal == null || !legal.playerId().value().equals(expectedActor.toString())) return false;
        int seat = seatFor(expectedActor);
        TableSeatState state = required(seat);
        seats.put(seat, copy(state, state.status(), true, state.standAfterHand(), state.pendingRebuy()));
        act(expectedActor, expectedSeq, legal.canCheck() ? GameAction.CHECK : GameAction.FOLD, null);
        return true;
    }

    boolean advancePresentation(long expectedHand, long expectedGeneration) {
        if (handId != expectedHand || scheduleGeneration != expectedGeneration || presentation.isEmpty()) return false;
        exposeNextTransition(); return true;
    }

    private void exposeNextTransition() {
        pausedSince = null;
        HandTransition step = presentation.removeFirst();
        presented = step.snapshotAfter();
        for (HandEvent event : step.events()) if (event instanceof HandEvent.PlayerActed acted) lastActions.put(acted.seat(), acted);
        deadline = null;
        if (presentation.isEmpty()) {
            if (presented.phase() == HandPhase.COMPLETE) finishHand();
            else nextActionOpportunity();
        }
        scheduleGeneration = increment(scheduleGeneration);
    }

    private void finishHand() {
        Map<Integer, TableSeatState> replacement = new HashMap<>(seats);
        for (PlayerSnapshot player : presented.players()) {
            TableSeatState old = replacement.get(player.seat());
            if (old != null) replacement.put(player.seat(), copy(old,
                    player.stack() == 0 ? TableSeatStatus.BUSTED : TableSeatStatus.ACTIVE,
                    old.sitOutAfterHand(), old.standAfterHand(), old.pendingRebuy(), player.stack()));
        }
        seats.clear(); seats.putAll(replacement);
        hand = null; status = TableStatus.WAITING; deadline = null;
    }

    boolean nextHand(long expectedHand, long expectedGeneration) {
        if (hand != null || handId != expectedHand || scheduleGeneration != expectedGeneration
                || !presentation.isEmpty()) {
            return false;
        }
        pausedSince = null;
        presented = null;
        applyBoundary();
        return true;
    }

    private void applyBoundary() {
        for (Integer seat : new ArrayList<>(seats.keySet())) {
            TableSeatState old = seats.get(seat);
            if (old.standAfterHand()) { seats.remove(seat); continue; }
            long stack = old.pendingRebuy() == null ? old.stack() : old.pendingRebuy();
            TableSeatStatus next = old.sitOutAfterHand() ? TableSeatStatus.SITTING_OUT
                    : stack == 0 ? TableSeatStatus.BUSTED : old.status() == TableSeatStatus.SITTING_OUT ? old.status() : TableSeatStatus.ACTIVE;
            seats.put(seat, copy(old, next, false, false, null, stack));
        }
        lifecycle();
        scheduleStartIfReady();
    }

    boolean startHand(long expectedLifecycle, long expectedGeneration) {
        if (hand != null || lifecycleSeq != expectedLifecycle
                || scheduleGeneration != expectedGeneration || nextHandAt == null
                || clock.instant().isBefore(nextHandAt)) {
            return false;
        }
        nextHandAt = null;
        try {
            startNow();
        } catch (RuntimeException ex) {
            hand = null;
            presented = null;
            presentation.clear();
            deadline = null;
            status = TableStatus.FAILED;
            lifecycle();
            scheduleGeneration = increment(scheduleGeneration);
        }
        return true;
    }

    boolean pause(long expectedGeneration) {
        if (scheduleGeneration != expectedGeneration
                || (status != TableStatus.RUNNING && nextHandAt == null)) {
            return false;
        }
        status = TableStatus.PAUSED;
        pausedDeadline = deadline;
        pausedNextHandAt = nextHandAt;
        if (pausedSince == null) pausedSince = clock.instant();
        deadline = null;
        nextHandAt = null;
        scheduleGeneration = increment(scheduleGeneration);
        lifecycle();
        return true;
    }

    boolean recoverOrFail(long expectedGeneration) {
        if (status != TableStatus.PAUSED || scheduleGeneration != expectedGeneration) {
            return false;
        }
        if (pausedSince != null && !clock.instant().isBefore(pausedSince.plusSeconds(300))) {
            hand = null;
            presented = null;
            presentation.clear();
            lastActions.clear();
            deadline = null;
            nextHandAt = null;
            status = TableStatus.FAILED;
            pausedSince = null;
        } else if (hand != null) {
            status = TableStatus.RUNNING;
            if (presentation.isEmpty() && presented != null && presented.actingSeat().isPresent()) {
                deadline = laterOf(pausedDeadline, clock.instant());
            }
        } else if (presented != null && presented.phase() == HandPhase.COMPLETE) {
            status = TableStatus.WAITING;
        } else {
            status = TableStatus.WAITING;
            nextHandAt = laterOf(pausedNextHandAt, clock.instant());
        }
        pausedDeadline = null;
        pausedNextHandAt = null;
        scheduleGeneration = increment(scheduleGeneration);
        lifecycle();
        return true;
    }

    private static Instant laterOf(Instant value, Instant floor) {
        return value == null || value.isBefore(floor) ? floor : value;
    }

    private void startNow() {
        pausedSince = null;
        List<TableSeatState> eligible = seats.values().stream()
                .filter(s -> s.stack() > 0 && s.status() == TableSeatStatus.ACTIVE && !s.standAfterHand())
                .sorted(Comparator.comparingInt(TableSeatState::seat)).toList();
        if (eligible.size() < 2) {
            status = TableStatus.WAITING;
            return;
        }
        int button = nextButton(eligible);
        List<PlayerAtSeat> players = eligible.stream().map(s -> new PlayerAtSeat(PlayerId.of(s.userId().toString()), s.seat(), s.stack())).toList();
        HoldemHand started = HoldemHand.start(new HandConfig(config.smallBlind(), config.bigBlind(), maxSeats), players, button, decks.create());
        hand = started; presented = started.snapshot(); lastButton = button; handId = increment(handId); lastActions.clear(); presentation.clear();
        status = TableStatus.RUNNING;
        nextActionOpportunity();
        lifecycle();
        scheduleGeneration = increment(scheduleGeneration);
    }

    private void scheduleStartIfReady() {
        if (hand != null || status == TableStatus.FAILED || status == TableStatus.PAUSED
                || (presented != null && presented.phase() == HandPhase.COMPLETE)) {
            clearPendingStart();
            return;
        }
        long eligible = seats.values().stream()
                .filter(s -> s.stack() > 0 && s.status() == TableSeatStatus.ACTIVE
                        && !s.standAfterHand())
                .count();
        if (eligible < 2) {
            status = TableStatus.WAITING;
            clearPendingStart();
            return;
        }
        status = TableStatus.WAITING;
        nextHandAt = clock.instant().plusSeconds(INTER_HAND_DELAY_SECONDS);
        scheduleGeneration = increment(scheduleGeneration);
    }

    private void clearPendingStart() {
        if (nextHandAt != null) {
            nextHandAt = null;
            scheduleGeneration = increment(scheduleGeneration);
        }
    }

    private int nextButton(List<TableSeatState> eligible) {
        if (lastButton == null) return eligible.get(0).seat();
        return eligible.stream().map(TableSeatState::seat).filter(s -> s > lastButton).findFirst().orElse(eligible.get(0).seat());
    }

    private void nextActionOpportunity() {
        if (presented.actingSeat().isPresent()) {
            actionSeq = increment(actionSeq);
            deadline = clock.instant().plusSeconds(config.actionTimeoutSeconds());
            status = TableStatus.RUNNING;
        }
    }

    GameState snapshot() {
        LegalActions legal = hand == null || deadline == null ? null : hand.legalActions().orElse(null);
        return new GameState(status, handId, actionSeq, deadline, presented, legal,
                seats.values().stream().sorted(Comparator.comparingInt(TableSeatState::seat)).toList(),
                lastActions, lifecycleSeq, scheduleGeneration, pausedSince, nextHandAt,
                hand == null && presentation.isEmpty());
    }

    boolean hasSeat(int seat, UUID userId) {
        TableSeatState state = seats.get(seat);
        return state != null && state.userId().equals(userId);
    }

    private boolean playerInHand(int seat) { return presented != null && presented.players().stream().anyMatch(p -> p.seat() == seat); }
    private int seatFor(UUID userId) { return seats.values().stream().filter(s -> s.userId().equals(userId)).map(TableSeatState::seat).findFirst().orElseThrow(() -> new GameException(GameError.NOT_SEATED)); }
    private TableSeatState required(int seat) { TableSeatState s = seats.get(seat); if (s == null) throw new GameException(GameError.NOT_SEATED); return s; }
    private void lifecycle() { lifecycleSeq = increment(lifecycleSeq); }
    private static long increment(long value) { try { return Math.incrementExact(value); } catch (ArithmeticException ex) { throw new IllegalStateException("sequence overflow", ex); } }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand, Long rebuy) { return copy(s,status,out,stand,rebuy,s.stack()); }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand, Long rebuy, long stack) {
        return new TableSeatState(s.seat(), s.userId(), s.initialBuyIn(), stack, status, out, stand, rebuy);
    }
}
