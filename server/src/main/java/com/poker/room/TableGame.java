package com.poker.room;

import com.poker.engine.game.*;
import com.poker.room.application.DeckFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Room-lock-confined adapter around the pure HoldemHand engine. */
final class TableGame {
    private static final long INTER_HAND_DELAY_SECONDS = 2;
    private static final long EMERGENCY_REBUY_OFFER_SECONDS = 5;
    private static final int MAX_HAND_HISTORIES = 20;
    private RoomConfig config;
    private final int maxSeats;
    private final Clock clock;
    private final DeckFactory decks;
    private final Map<Integer, TableSeatState> seats = new HashMap<>();
    private final Map<UUID, TablePlayerState> players = new HashMap<>();
    private final Set<UUID> roomMembers = new HashSet<>();
    private final ArrayDeque<UUID> emergencyRebuyQueue = new ArrayDeque<>();
    private final ArrayDeque<HandHistory> completedHistories = new ArrayDeque<>();
    private final Map<Integer, List<HandEvent.PlayerActed>> roundActions = new HashMap<>();
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
    private long bustOrder;
    private UUID rebuyOfferUserId;
    private Instant rebuyOfferExpiresAt;
    private HandHistory currentHistory;
    private long historyEventSeq;

    TableGame(RoomConfig config, int maxSeats, Clock clock, DeckFactory decks) {
        this.config = config; this.maxSeats = maxSeats; this.clock = clock; this.decks = decks;
    }

    void updateConfig(RoomConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        refreshEmergencyRebuyOffer();
    }

    void memberJoined(UUID userId) {
        roomMembers.add(Objects.requireNonNull(userId, "userId"));
        refreshEmergencyRebuyOffer();
    }

    void memberLeft(UUID userId) {
        roomMembers.remove(Objects.requireNonNull(userId, "userId"));
        refreshEmergencyRebuyOffer();
    }

    void seat(int seat, UUID userId, long buyIn) {
        config.validateBuyIn(buyIn);
        TableSeatState existing = seats.get(seat);
        if (existing != null) {
            if (existing.userId().equals(userId) && existing.initialBuyIn() == buyIn) return;
            throw new RoomException(RoomError.ALREADY_SEATED);
        }
        TablePlayerState previousBalance = players.get(userId);
        if (previousBalance != null && previousBalance.stack() == 0) {
            throw new GameException(GameError.REBUY_NOT_AVAILABLE);
        }
        long balance = previousBalance == null ? buyIn : previousBalance.stack();
        long previousLifecycle = lifecycleSeq;
        seats.put(seat, new TableSeatState(
                seat, userId, buyIn, balance,
                balance == 0 ? TableSeatStatus.BUSTED : TableSeatStatus.ACTIVE,
                false, false, null,
                previousBalance == null ? 0 : previousBalance.rebuyHandsRemaining(),
                previousBalance == null ? 0 : previousBalance.rebuyCount()));
        try {
            refreshEmergencyRebuyOffer();
            lifecycle();
            scheduleStartIfReady();
        } catch (RuntimeException ex) {
            seats.remove(seat);
            if (previousBalance == null) players.remove(userId);
            else players.put(userId, previousBalance);
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
        refreshEmergencyRebuyOffer();
        lifecycle();
        scheduleStartIfReady();
        return true;
    }

    void removeAtBoundary(int seat) {
        seats.remove(seat);
        refreshEmergencyRebuyOffer();
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
        refreshEmergencyRebuyOffer();
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

    void rebuy(UUID userId, long amount) {
        TablePlayerState player = players.get(userId);
        boolean stillOnTable = seats.values().stream().anyMatch(seat -> seat.userId().equals(userId));
        boolean emergencyOffer = userId.equals(rebuyOfferUserId)
                && rebuyOfferExpiresAt != null && clock.instant().isBefore(rebuyOfferExpiresAt);
        if (!config.rebuysEnabled() || player == null || player.stack() > 0
                || (player.rebuyHandsRemaining() > 0 && !emergencyOffer) || stillOnTable) {
            throw new GameException(GameError.REBUY_NOT_AVAILABLE);
        }
        if (amount != config.rebuyStack()) throw new GameException(GameError.INVALID_BUY_IN);
        players.put(userId, new TablePlayerState(
                amount, 0, Math.incrementExact(player.rebuyCount()), 0));
        refreshEmergencyRebuyOffer();
        lifecycle();
    }

    boolean rotateEmergencyRebuyOffer(long expectedGeneration, UUID expectedUser) {
        if (scheduleGeneration != expectedGeneration || !Objects.equals(rebuyOfferUserId, expectedUser)
                || rebuyOfferExpiresAt == null || clock.instant().isBefore(rebuyOfferExpiresAt)) {
            return false;
        }
        UUID expired = emergencyRebuyQueue.pollFirst();
        if (expired != null) emergencyRebuyQueue.addLast(expired);
        rebuyOfferUserId = emergencyRebuyQueue.peekFirst();
        rebuyOfferExpiresAt = rebuyOfferUserId == null
                ? null : clock.instant().plusSeconds(EMERGENCY_REBUY_OFFER_SECONDS);
        scheduleGeneration = increment(scheduleGeneration);
        lifecycle();
        return true;
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
        if (step.events().stream().anyMatch(HandEvent.StreetDealt.class::isInstance)) {
            roundActions.clear();
        }
        presented = step.snapshotAfter();
        appendHistory(step);
        for (HandEvent event : step.events()) {
            if (event instanceof HandEvent.PlayerActed acted) {
                roundActions.computeIfAbsent(acted.seat(), ignored -> new ArrayList<>()).add(acted);
            }
        }
        deadline = null;
        if (presentation.isEmpty()) {
            if (presented.phase() == HandPhase.COMPLETE) finishHand();
            else nextActionOpportunity();
        }
        scheduleGeneration = increment(scheduleGeneration);
    }

    private void finishHand() {
        archiveCurrentHistory();
        players.replaceAll((userId, player) -> player.stack() == 0 && player.rebuyHandsRemaining() > 0
                ? new TablePlayerState(0, player.rebuyHandsRemaining() - 1,
                        player.rebuyCount(), player.bustedOrder())
                : player);
        Map<Integer, TableSeatState> replacement = new HashMap<>(seats);
        for (PlayerSnapshot player : presented.players()) {
            TableSeatState old = replacement.get(player.seat());
            if (old != null) {
                boolean newlyBusted = player.stack() == 0 && old.stack() > 0;
                replacement.put(player.seat(), copy(old,
                        player.stack() == 0 ? TableSeatStatus.BUSTED : TableSeatStatus.ACTIVE,
                        old.sitOutAfterHand(), old.standAfterHand(), old.pendingRebuy(), player.stack(),
                        newlyBusted ? config.rebuyDelayHands() : old.rebuyHandsRemaining()));
            }
        }
        seats.clear(); seats.putAll(replacement);
        rememberPlayerBalances();
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
            if (old.stack() == 0) { seats.remove(seat); continue; }
            long stack = old.pendingRebuy() == null ? old.stack() : old.pendingRebuy();
            int rebuyCount = old.pendingRebuy() == null
                    ? old.rebuyCount() : Math.incrementExact(old.rebuyCount());
            TableSeatStatus next = old.sitOutAfterHand() ? TableSeatStatus.SITTING_OUT
                    : stack == 0 ? TableSeatStatus.BUSTED : old.status() == TableSeatStatus.SITTING_OUT ? old.status() : TableSeatStatus.ACTIVE;
            seats.put(seat, copy(old, next, false, false, null, stack,
                    stack > 0 ? 0 : old.rebuyHandsRemaining(),
                    rebuyCount));
        }
        refreshEmergencyRebuyOffer();
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
            currentHistory = null;
            presentation.clear();
            roundActions.clear();
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
            currentHistory = null;
            presentation.clear();
            roundActions.clear();
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
        hand = started;
        presented = started.snapshot();
        lastButton = button;
        handId = increment(handId);
        beginHistory(eligible, started);
        roundActions.clear();
        presentation.clear();
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
        List<HandHistory> histories = new ArrayList<>(completedHistories.size() + 1);
        if (currentHistory != null) histories.add(currentHistory);
        histories.addAll(completedHistories);
        return new GameState(status, handId, actionSeq, deadline, presented, legal,
                seats.values().stream().sorted(Comparator.comparingInt(TableSeatState::seat)).toList(),
                players, roundActions, lifecycleSeq, scheduleGeneration, pausedSince, nextHandAt,
                rebuyOfferUserId, rebuyOfferExpiresAt,
                histories,
                hand == null && presentation.isEmpty());
    }

    boolean hasSeat(int seat, UUID userId) {
        TableSeatState state = seats.get(seat);
        return state != null && state.userId().equals(userId);
    }

    private boolean playerInHand(int seat) { return presented != null && presented.players().stream().anyMatch(p -> p.seat() == seat); }
    private int seatFor(UUID userId) { return seats.values().stream().filter(s -> s.userId().equals(userId)).map(TableSeatState::seat).findFirst().orElseThrow(() -> new GameException(GameError.NOT_SEATED)); }
    private TableSeatState required(int seat) { TableSeatState s = seats.get(seat); if (s == null) throw new GameException(GameError.NOT_SEATED); return s; }
    private void lifecycle() {
        rememberPlayerBalances();
        lifecycleSeq = increment(lifecycleSeq);
    }
    private void rememberPlayerBalances() {
        for (TableSeatState seat : seats.values().stream()
                .sorted(Comparator.comparingInt(TableSeatState::seat)).toList()) {
            TablePlayerState previous = players.get(seat.userId());
            long order = 0;
            if (seat.stack() == 0) {
                order = previous != null && previous.stack() == 0
                        ? previous.bustedOrder() : increment(bustOrder);
                bustOrder = Math.max(bustOrder, order);
            }
            players.put(seat.userId(), new TablePlayerState(
                    seat.stack(), seat.rebuyHandsRemaining(), seat.rebuyCount(), order));
        }
    }

    private void refreshEmergencyRebuyOffer() {
        Set<UUID> seatedUsers = seats.values().stream().map(TableSeatState::userId)
                .collect(java.util.stream.Collectors.toSet());
        long fundedSeats = seats.values().stream().filter(seat -> seat.stack() > 0).count();
        boolean fundedOffTable = players.entrySet().stream()
                .anyMatch(entry -> roomMembers.contains(entry.getKey())
                        && entry.getValue().stack() > 0 && !seatedUsers.contains(entry.getKey()));
        List<UUID> eligible = players.entrySet().stream()
                .filter(entry -> roomMembers.contains(entry.getKey())
                        && entry.getValue().stack() == 0)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().bustedOrder()))
                .map(Map.Entry::getKey).toList();
        if (!config.rebuysEnabled() || fundedSeats != 1 || fundedOffTable || eligible.isEmpty()) {
            clearEmergencyRebuyOffer();
            return;
        }
        emergencyRebuyQueue.removeIf(userId -> !eligible.contains(userId));
        for (UUID userId : eligible) {
            if (!emergencyRebuyQueue.contains(userId)) emergencyRebuyQueue.addLast(userId);
        }
        if (!Objects.equals(rebuyOfferUserId, emergencyRebuyQueue.peekFirst())
                || rebuyOfferExpiresAt == null) {
            rebuyOfferUserId = emergencyRebuyQueue.peekFirst();
            rebuyOfferExpiresAt = clock.instant().plusSeconds(EMERGENCY_REBUY_OFFER_SECONDS);
            scheduleGeneration = increment(scheduleGeneration);
        }
    }

    private void clearEmergencyRebuyOffer() {
        emergencyRebuyQueue.clear();
        if (rebuyOfferUserId != null || rebuyOfferExpiresAt != null) {
            rebuyOfferUserId = null;
            rebuyOfferExpiresAt = null;
            scheduleGeneration = increment(scheduleGeneration);
        }
    }

    private void beginHistory(List<TableSeatState> eligible, HoldemHand started) {
        Instant now = clock.instant();
        historyEventSeq = 0;
        List<HandHistoryEvent> events = new ArrayList<>();
        for (HandTransition transition : started.initialTransitions()) {
            appendEvents(events, transition, now);
        }
        List<HandHistory.Player> historyPlayers = eligible.stream()
                .map(seat -> new HandHistory.Player(seat.userId(), seat.seat(), seat.stack()))
                .toList();
        currentHistory = new HandHistory(
                handId, now,
                presented.phase() == HandPhase.COMPLETE ? now : null,
                config.smallBlind(), config.bigBlind(), historyPlayers, events, presented);
    }

    private void appendHistory(HandTransition transition) {
        if (currentHistory == null) return;
        List<HandHistoryEvent> events = new ArrayList<>(currentHistory.events());
        Instant now = clock.instant();
        appendEvents(events, transition, now);
        Instant completedAt = transition.snapshotAfter().phase() == HandPhase.COMPLETE
                ? now : currentHistory.completedAt();
        currentHistory = new HandHistory(
                currentHistory.handId(), currentHistory.startedAt(), completedAt,
                currentHistory.smallBlind(), currentHistory.bigBlind(), currentHistory.players(),
                events, transition.snapshotAfter());
    }

    private void appendEvents(
            List<HandHistoryEvent> destination,
            HandTransition transition,
            Instant occurredAt) {
        for (HandEvent event : transition.events()) {
            HandPhase phase = event instanceof HandEvent.StreetDealt dealt
                    ? dealt.phase() : transition.snapshotAfter().phase();
            historyEventSeq = increment(historyEventSeq);
            destination.add(new HandHistoryEvent(historyEventSeq, occurredAt, phase, event));
        }
    }

    private void archiveCurrentHistory() {
        if (currentHistory == null) return;
        Instant completedAt = currentHistory.completedAt() == null
                ? clock.instant() : currentHistory.completedAt();
        HandHistory completed = new HandHistory(
                currentHistory.handId(), currentHistory.startedAt(), completedAt,
                currentHistory.smallBlind(), currentHistory.bigBlind(), currentHistory.players(),
                currentHistory.events(), presented);
        completedHistories.addFirst(completed);
        while (completedHistories.size() > MAX_HAND_HISTORIES) completedHistories.removeLast();
        currentHistory = null;
    }
    private static long increment(long value) { try { return Math.incrementExact(value); } catch (ArithmeticException ex) { throw new IllegalStateException("sequence overflow", ex); } }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand, Long rebuy) { return copy(s,status,out,stand,rebuy,s.stack()); }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand, Long rebuy, long stack) {
        return copy(s, status, out, stand, rebuy, stack, s.rebuyHandsRemaining());
    }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand,
                                        Long rebuy, long stack, int rebuyHandsRemaining) {
        return copy(s, status, out, stand, rebuy, stack, rebuyHandsRemaining, s.rebuyCount());
    }
    private static TableSeatState copy(TableSeatState s, TableSeatStatus status, boolean out, boolean stand,
                                       Long rebuy, long stack, int rebuyHandsRemaining, int rebuyCount) {
        return new TableSeatState(s.seat(), s.userId(), s.initialBuyIn(), stack, status, out, stand,
                rebuy, rebuyHandsRemaining, rebuyCount);
    }
}
