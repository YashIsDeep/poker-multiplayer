package com.poker.engine.game;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.Deck;
import com.poker.engine.cards.HandEvaluator;
import com.poker.engine.cards.HandRank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A deterministic, room-lock-confined Texas Hold'em hand aggregate.
 *
 * <p>This class deliberately has no transport, persistence, clock, scheduler,
 * or randomness dependency. The caller supplies the complete deck order.</p>
 */
public final class HoldemHand {
    static final class PlayerState {
        final PlayerId id;
        final int seat;
        long stack;
        long streetCommitted;
        long handCommitted;
        boolean folded;
        boolean acted;
        long lastActedFacingBet;
        final ArrayList<Card> holeCards = new ArrayList<>(2);

        PlayerState(PlayerId id, int seat, long stack) {
            this.id = id;
            this.seat = seat;
            this.stack = stack;
        }

        boolean allIn() {
            return stack == 0;
        }
    }

    private final HandConfig config;
    private final List<PlayerState> players;
    private final int buttonSeat;
    private final int smallBlindSeat;
    private final int bigBlindSeat;
    private final long totalChipsAtStart;
    private final List<Card> drawOrder;
    private final BettingRound round = new BettingRound();
    private final ArrayList<Card> board = new ArrayList<>(5);
    private final ArrayList<Card> burns = new ArrayList<>(3);
    private final ArrayList<PotAward> awards = new ArrayList<>();
    private final ArrayList<HandTransition> initialTransitions = new ArrayList<>();

    private int drawCursor;
    private HandPhase phase = HandPhase.PREFLOP;
    private CompletionReason completionReason;
    private Integer actorSeat;
    private int lastActionSeat;
    private boolean settling;
    private long nextSequence = 1;

    private HoldemHand(HandConfig config,
                       List<PlayerState> players,
                       int buttonSeat,
                       int smallBlindSeat,
                       int bigBlindSeat,
                       long totalChipsAtStart,
                       List<Card> drawOrder) {
        this.config = config;
        this.players = List.copyOf(players);
        this.buttonSeat = buttonSeat;
        this.smallBlindSeat = smallBlindSeat;
        this.bigBlindSeat = bigBlindSeat;
        this.totalChipsAtStart = totalChipsAtStart;
        this.drawOrder = List.copyOf(drawOrder);
        this.lastActionSeat = buttonSeat;
    }

    public static HoldemHand start(HandConfig config,
                                   List<PlayerAtSeat> players,
                                   int buttonSeat,
                                   Deck deck) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(deck, "deck");
        if (deck.remaining() != 52) {
            throw new IllegalArgumentException("HoldemHand requires a fresh 52-card deck");
        }
        if (players.size() < 2 || players.size() > config.maxSeats()) {
            throw new IllegalArgumentException("player count must be between 2 and maxSeats");
        }

        ArrayList<PlayerAtSeat> input = new ArrayList<>(players.size());
        HashSet<PlayerId> ids = new HashSet<>();
        HashSet<Integer> seats = new HashSet<>();
        long total = 0;
        boolean buttonParticipates = false;
        for (PlayerAtSeat player : players) {
            Objects.requireNonNull(player, "players cannot contain null");
            if (player.seat() >= config.maxSeats()) {
                throw new IllegalArgumentException("seat exceeds maxSeats: " + player.seat());
            }
            if (!ids.add(player.id())) {
                throw new IllegalArgumentException("duplicate player ID: " + player.id());
            }
            if (!seats.add(player.seat())) {
                throw new IllegalArgumentException("duplicate seat: " + player.seat());
            }
            try {
                total = Math.addExact(total, player.stack());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("starting chip total overflows long", overflow);
            }
            if (player.seat() == buttonSeat) buttonParticipates = true;
            input.add(player);
        }
        if (!buttonParticipates) {
            throw new IllegalArgumentException("buttonSeat must identify a participating player");
        }

        input.sort(Comparator.comparingInt(PlayerAtSeat::seat));
        ArrayList<PlayerState> state = new ArrayList<>(input.size());
        for (PlayerAtSeat player : input) state.add(new PlayerState(player.id(), player.seat(), player.stack()));

        List<Integer> occupied = occupiedSeats(state);
        int smallBlind;
        int bigBlind;
        if (state.size() == 2) {
            smallBlind = buttonSeat;
            bigBlind = SeatOrder.nextOccupied(occupied, buttonSeat);
        } else {
            smallBlind = SeatOrder.nextOccupied(occupied, buttonSeat);
            bigBlind = SeatOrder.nextOccupied(occupied, smallBlind);
        }

        // All validation above happens before this single deck-consuming operation.
        List<Card> privateDrawOrder = deck.draw(52);
        HoldemHand hand = new HoldemHand(config, state, buttonSeat, smallBlind, bigBlind, total, privateDrawOrder);
        hand.initialize();
        return hand;
    }

    public List<HandTransition> initialTransitions() {
        return List.copyOf(initialTransitions);
    }

    public HandSnapshot snapshot() {
        verifyInvariants();
        return snapshotFrame();
    }

    public Optional<LegalActions> legalActions() {
        if (phase == HandPhase.COMPLETE || actorSeat == null) return Optional.empty();
        PlayerState actor = playerAtSeat(actorSeat);
        if (actor == null || actor.folded || actor.allIn()) {
            throw new IllegalStateException("acting seat is not actionable");
        }
        return Optional.of(legalActionsFor(actor));
    }

    public ActionResult act(PlayerId playerId, PlayerAction action) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        if (phase == HandPhase.COMPLETE) reject(ActionRejectionReason.HAND_COMPLETE);

        PlayerState player = playerById(playerId);
        if (player == null) reject(ActionRejectionReason.UNKNOWN_PLAYER);
        if (actorSeat == null || player.seat != actorSeat) reject(ActionRejectionReason.NOT_ACTOR);

        LegalActions legal = legalActionsFor(player);
        validateAction(legal, action);

        ArrayList<HandTransition> transitions = new ArrayList<>();
        ArrayList<HandEvent> actionEvents = new ArrayList<>(2);
        applyAction(player, action, actionEvents);
        lastActionSeat = player.seat;

        boolean closes = roundClosed();
        if (closes) {
            actorSeat = null;
            refundUnmatched().ifPresent(actionEvents::add);
        } else {
            actorSeat = nextActionableAfter(lastActionSeat);
            if (actorSeat == null) throw new IllegalStateException("open betting round has no actor");
        }
        capture(actionEvents, transitions);

        if (closes) {
            if (livePlayers() <= 1) {
                settle(CompletionReason.FOLD_WIN, transitions);
            } else if (phase == HandPhase.RIVER) {
                settle(CompletionReason.SHOWDOWN, transitions);
            } else {
                advanceClosed(transitions);
            }
        }
        return new ActionResult(snapshotFrame(), transitions);
    }

    private void initialize() {
        round.startPreflop(config.bigBlind());
        ArrayList<HandEvent> events = new ArrayList<>();
        postBlind(smallBlindSeat, BlindType.SMALL_BLIND, config.smallBlind(), events);
        postBlind(bigBlindSeat, BlindType.BIG_BLIND, config.bigBlind(), events);

        int dealStart = players.size() == 2 ? buttonSeat : smallBlindSeat;
        for (int cardIndex = 0; cardIndex < 2; cardIndex++) {
            for (int seat : SeatOrder.circularFrom(occupiedSeats(), dealStart)) {
                PlayerState player = playerAtSeat(seat);
                Card card = drawNext();
                player.holeCards.add(card);
                events.add(new HandEvent.HoleCardDealt(player.id, player.seat, cardIndex, card));
            }
        }

        boolean closes = roundClosed();
        if (closes) {
            actorSeat = null;
            refundUnmatched().ifPresent(events::add);
        } else {
            int start = players.size() == 2 ? buttonSeat : SeatOrder.nextOccupied(occupiedSeats(), bigBlindSeat);
            actorSeat = firstActionableFrom(start);
            if (actorSeat == null) throw new IllegalStateException("preflop has no actor despite open round");
        }
        capture(events, initialTransitions);

        if (closes) {
            if (livePlayers() <= 1) settle(CompletionReason.FOLD_WIN, initialTransitions);
            else advanceClosed(initialTransitions);
        }
    }

    private void advanceClosed(List<HandTransition> transitions) {
        while (phase != HandPhase.COMPLETE) {
            if (livePlayers() <= 1) {
                settle(CompletionReason.FOLD_WIN, transitions);
                return;
            }
            if (phase == HandPhase.RIVER) {
                settle(CompletionReason.SHOWDOWN, transitions);
                return;
            }
            dealNextStreet(transitions);
            if (!roundClosed()) return;
        }
    }

    private void dealNextStreet(List<HandTransition> transitions) {
        phase = switch (phase) {
            case PREFLOP -> HandPhase.FLOP;
            case FLOP -> HandPhase.TURN;
            case TURN -> HandPhase.RIVER;
            case RIVER, COMPLETE -> throw new IllegalStateException("cannot deal after river");
        };
        for (PlayerState player : players) {
            player.streetCommitted = 0;
            player.acted = false;
            player.lastActedFacingBet = 0;
        }
        round.startPostflop(config.bigBlind());
        actorSeat = null;
        lastActionSeat = buttonSeat;

        Card burn = drawNext(); // burn card is intentionally never placed in a public event or snapshot
        burns.add(burn);
        int cardsToDeal = phase == HandPhase.FLOP ? 3 : 1;
        ArrayList<Card> added = new ArrayList<>(cardsToDeal);
        for (int i = 0; i < cardsToDeal; i++) {
            Card card = drawNext();
            board.add(card);
            added.add(card);
        }

        boolean closes = roundClosed();
        if (!closes) {
            actorSeat = firstActionableFrom(SeatOrder.nextOccupied(occupiedSeats(), buttonSeat));
            if (actorSeat == null) throw new IllegalStateException("postflop has no actor despite open round");
        }
        capture(List.of(new HandEvent.StreetDealt(phase, added, board)), transitions);
    }

    private void settle(CompletionReason reason, List<HandTransition> transitions) {
        if (phase == HandPhase.COMPLETE) throw new IllegalStateException("hand already settled");
        settling = true;
        PlayerState foldWinner = null;
        if (reason == CompletionReason.FOLD_WIN) {
            for (PlayerState player : players) {
                if (!player.folded) {
                    if (foldWinner != null) throw new IllegalStateException("fold win has multiple live players");
                    foldWinner = player;
                }
            }
            if (foldWinner == null) throw new IllegalStateException("fold win has no live player");
        }

        for (PlayerState player : players) player.streetCommitted = 0;
        round.currentBet = 0;
        round.lastFullRaiseSize = config.bigBlind();
        actorSeat = null;
        List<Pot> pots = PotSettlement.settlementPots(players, reason == CompletionReason.FOLD_WIN, foldWinner);
        long previousCap = 0;
        for (int potIndex = 0; potIndex < pots.size(); potIndex++) {
            Pot pot = pots.get(potIndex);
            long width;
            try {
                width = Math.subtractExact(pot.cap(), previousCap);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("pot cap overflow", overflow);
            }
            for (PlayerState player : players) {
                if (pot.contributors().contains(player.id)) {
                    player.handCommitted = checkedSubtract(player.handCommitted, width);
                }
            }

            List<PlayerState> winners = reason == CompletionReason.FOLD_WIN
                    ? List.of(foldWinner)
                    : showdownWinners(pot.eligible());
            List<WinnerAward> winnerAwards = allocatePot(pot.amount(), winners);
            for (WinnerAward award : winnerAwards) {
                PlayerState winner = playerById(award.playerId());
                winner.stack = checkedAdd(winner.stack, award.amount());
            }
            PotAward potAward = new PotAward(potIndex, pot.amount(), winnerAwards);
            awards.add(potAward);
            capture(List.of(new HandEvent.PotAwarded(potIndex, pot.amount(), winnerAwards)), transitions);
            previousCap = pot.cap();
        }

        for (PlayerState player : players) {
            if (player.handCommitted != 0) throw new IllegalStateException("settlement left a commitment");
        }
        completionReason = reason;
        phase = HandPhase.COMPLETE;
        actorSeat = null;
        round.currentBet = 0;
        settling = false;
        capture(List.of(new HandEvent.HandCompleted(reason)), transitions);
    }

    private List<PlayerState> showdownWinners(List<PlayerId> eligibleIds) {
        if (board.size() != 5) throw new IllegalStateException("showdown requires a five-card board");
        HandRank best = null;
        ArrayList<PlayerState> winners = new ArrayList<>();
        for (PlayerId id : eligibleIds) {
            PlayerState player = playerById(id);
            if (player.holeCards.size() != 2) throw new IllegalStateException("showdown player lacks hole cards");
            ArrayList<Card> seven = new ArrayList<>(7);
            seven.addAll(player.holeCards);
            seven.addAll(board);
            HandRank rank = HandEvaluator.evaluate7(seven);
            int comparison = best == null ? 1 : rank.compareTo(best);
            if (comparison > 0) {
                best = rank;
                winners.clear();
                winners.add(player);
            } else if (comparison == 0) {
                winners.add(player);
            }
        }
        if (winners.isEmpty()) throw new IllegalStateException("pot has no showdown winner");
        return orderFromButton(winners);
    }

    private List<WinnerAward> allocatePot(long amount, List<PlayerState> unorderedWinners) {
        List<PlayerState> winners = orderFromButton(unorderedWinners);
        long base = amount / winners.size();
        int remainder = (int) (amount % winners.size());
        ArrayList<WinnerAward> awards = new ArrayList<>(winners.size());
        for (int i = 0; i < winners.size(); i++) {
            long share = checkedAdd(base, i < remainder ? 1 : 0);
            awards.add(new WinnerAward(winners.get(i).id, winners.get(i).seat, share));
        }
        return List.copyOf(awards);
    }

    private List<PlayerState> orderFromButton(List<PlayerState> unordered) {
        ArrayList<PlayerState> ordered = new ArrayList<>(unordered.size());
        int seat = SeatOrder.nextOccupied(occupiedSeats(), buttonSeat);
        for (int i = 0; i < players.size(); i++) {
            PlayerState candidate = playerAtSeat(seat);
            if (unordered.contains(candidate)) ordered.add(candidate);
            seat = SeatOrder.nextOccupied(occupiedSeats(), seat);
        }
        if (ordered.size() != unordered.size()) throw new IllegalStateException("winner ordering lost a player");
        return List.copyOf(ordered);
    }

    private void validateAction(LegalActions legal, PlayerAction action) {
        if (action instanceof PlayerAction.Fold) return;
        if (action instanceof PlayerAction.Check) {
            if (!legal.canCheck()) reject(ActionRejectionReason.ACTION_NOT_LEGAL);
            return;
        }
        if (action instanceof PlayerAction.Call) {
            if (!legal.canCall()) reject(ActionRejectionReason.ACTION_NOT_LEGAL);
            return;
        }
        if (action instanceof PlayerAction.RaiseTo raiseTo) {
            if (!legal.canRaise()) reject(ActionRejectionReason.ACTION_NOT_LEGAL);
            if (raiseTo.amount() < legal.minimumRaiseTo() || raiseTo.amount() > legal.maximumRaiseTo()) {
                reject(ActionRejectionReason.INVALID_RAISE_TO);
            }
            return;
        }
        throw new IllegalStateException("unknown sealed player action");
    }

    private void applyAction(PlayerState player, PlayerAction action, List<HandEvent> events) {
        long facingBet = round.currentBet;
        long amountCommitted = 0;
        if (action instanceof PlayerAction.Fold) {
            player.folded = true;
            player.acted = true;
            player.lastActedFacingBet = facingBet;
        } else if (action instanceof PlayerAction.Check) {
            player.acted = true;
            player.lastActedFacingBet = facingBet;
        } else if (action instanceof PlayerAction.Call) {
            long toCall = Math.max(0, checkedSubtract(round.currentBet, player.streetCommitted));
            amountCommitted = Math.min(toCall, player.stack);
            addContribution(player, amountCommitted);
            player.acted = true;
            player.lastActedFacingBet = facingBet;
        } else if (action instanceof PlayerAction.RaiseTo raiseTo) {
            amountCommitted = checkedSubtract(raiseTo.amount(), player.streetCommitted);
            addContribution(player, amountCommitted);
            long raiseSize = checkedSubtract(raiseTo.amount(), round.currentBet);
            round.currentBet = raiseTo.amount();
            round.lastFullRaiseSize = isFullRaise(raiseTo.amount(), facingBet)
                    ? raiseSize
                    : round.lastFullRaiseSize;
            player.acted = true;
            player.lastActedFacingBet = round.currentBet;
        } else {
            throw new IllegalStateException("unknown sealed player action");
        }
        events.add(new HandEvent.PlayerActed(player.id, player.seat, action, amountCommitted,
                player.streetCommitted, player.stack));
    }

    private boolean isFullRaise(long raiseTo, long facingBet) {
        if (facingBet == 0) return raiseTo >= config.bigBlind();
        try {
            return raiseTo >= Math.addExact(facingBet, round.lastFullRaiseSize);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private LegalActions legalActionsFor(PlayerState player) {
        long toCall = Math.max(0, checkedSubtract(round.currentBet, player.streetCommitted));
        long callAmount = Math.min(toCall, player.stack);
        long maximumRaiseTo = checkedAdd(player.streetCommitted, player.stack);
        boolean canRaise = canRaise(player) && maximumRaiseTo > round.currentBet;
        long minimumRaiseTo = 0;
        if (canRaise) {
            Long fullMinimum = fullMinimumRaiseTo();
            if (fullMinimum != null && maximumRaiseTo >= fullMinimum) {
                minimumRaiseTo = fullMinimum;
            } else {
                // The only under-minimum raise exposed is an all-in short raise.
                minimumRaiseTo = maximumRaiseTo;
            }
            if (minimumRaiseTo <= round.currentBet) {
                canRaise = false;
                minimumRaiseTo = 0;
                maximumRaiseTo = 0;
            }
        } else {
            maximumRaiseTo = 0;
        }
        return new LegalActions(player.id, toCall, true, toCall == 0, toCall > 0,
                callAmount, canRaise, minimumRaiseTo, canRaise ? maximumRaiseTo : 0);
    }

    private Long fullMinimumRaiseTo() {
        if (round.currentBet == 0) return config.bigBlind();
        try {
            return Math.addExact(round.currentBet, round.lastFullRaiseSize);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private boolean canRaise(PlayerState player) {
        if (!player.acted) return true;
        long increase = checkedSubtract(round.currentBet, player.lastActedFacingBet);
        return increase >= round.lastFullRaiseSize;
    }

    private boolean roundClosed() {
        if (livePlayers() <= 1) return true;
        ArrayList<PlayerState> liveWithChips = new ArrayList<>();
        for (PlayerState player : players) {
            if (!player.folded && !player.allIn()) liveWithChips.add(player);
        }
        if (liveWithChips.isEmpty()) return true;
        if (liveWithChips.size() == 1) {
            return liveWithChips.get(0).streetCommitted >= round.currentBet;
        }
        for (PlayerState player : liveWithChips) {
            if (!player.acted || player.streetCommitted < round.currentBet) return false;
        }
        return true;
    }

    private int livePlayers() {
        int count = 0;
        for (PlayerState player : players) if (!player.folded) count++;
        return count;
    }

    private int nextActionableAfter(int seat) {
        return firstActionableFrom(SeatOrder.nextOccupied(occupiedSeats(), seat));
    }

    private Integer firstActionableFrom(int startSeat) {
        int seat = startSeat;
        for (int i = 0; i < players.size(); i++) {
            PlayerState player = playerAtSeat(seat);
            if (!player.folded && !player.allIn()
                    && (!player.acted || player.streetCommitted < round.currentBet)) return seat;
            seat = SeatOrder.nextOccupied(occupiedSeats(), seat);
        }
        return null;
    }

    private void postBlind(int seat, BlindType type, long nominal, List<HandEvent> events) {
        PlayerState player = playerAtSeat(seat);
        long amount = Math.min(nominal, player.stack);
        addContribution(player, amount);
        events.add(new HandEvent.BlindPosted(player.id, player.seat, type, amount, player.allIn()));
    }

    private Optional<HandEvent.UncalledBetRefunded> refundUnmatched() {
        PlayerState leader = null;
        long highest = -1;
        long second = 0;
        int leaders = 0;
        for (PlayerState player : players) {
            long contribution = player.handCommitted;
            if (contribution > highest) {
                second = highest < 0 ? 0 : highest;
                highest = contribution;
                leader = player;
                leaders = 1;
            } else if (contribution == highest && contribution > 0) {
                leaders++;
            } else if (contribution > second) {
                second = contribution;
            }
        }
        if (leader == null || leaders != 1 || highest <= second) return Optional.empty();
        long refund = checkedSubtract(highest, second);
        if (refund > leader.streetCommitted) {
            throw new IllegalStateException("uncalled refund exceeds current-street commitment");
        }
        leader.handCommitted = checkedSubtract(leader.handCommitted, refund);
        leader.streetCommitted = checkedSubtract(leader.streetCommitted, refund);
        leader.stack = checkedAdd(leader.stack, refund);
        if (phase != HandPhase.PREFLOP) round.currentBet = maxStreetCommitment();
        return Optional.of(new HandEvent.UncalledBetRefunded(leader.id, leader.seat, refund, leader.stack));
    }

    private long maxStreetCommitment() {
        long maximum = 0;
        for (PlayerState player : players) maximum = Math.max(maximum, player.streetCommitted);
        return maximum;
    }

    private void addContribution(PlayerState player, long amount) {
        if (amount < 0 || amount > player.stack) throw new IllegalStateException("invalid contribution amount");
        player.stack = checkedSubtract(player.stack, amount);
        player.streetCommitted = checkedAdd(player.streetCommitted, amount);
        player.handCommitted = checkedAdd(player.handCommitted, amount);
    }

    private Card drawNext() {
        if (drawCursor >= drawOrder.size()) throw new IllegalStateException("hand ran out of cards");
        return drawOrder.get(drawCursor++);
    }

    private void capture(List<HandEvent> events, List<HandTransition> transitions) {
        if (events.isEmpty()) throw new IllegalStateException("cannot capture an empty transition");
        verifyInvariants();
        transitions.add(new HandTransition(nextSequence++, events, snapshotFrame()));
    }

    private HandSnapshot snapshotFrame() {
        PotSettlement.View potView = phase == HandPhase.COMPLETE
                ? new PotSettlement.View(List.of(), Optional.empty())
                : PotSettlement.activeView(players);
        ArrayList<PlayerSnapshot> playerSnapshots = new ArrayList<>(players.size());
        for (PlayerState player : players) {
            playerSnapshots.add(new PlayerSnapshot(player.id, player.seat, player.stack,
                    player.streetCommitted, player.handCommitted, player.folded, player.allIn(), player.holeCards));
        }
        OptionalInt acting = actorSeat == null ? OptionalInt.empty() : OptionalInt.of(actorSeat);
        return new HandSnapshot(phase, Optional.ofNullable(completionReason), buttonSeat, smallBlindSeat,
                bigBlindSeat, acting, phase == HandPhase.COMPLETE ? 0 : round.currentBet,
                round.lastFullRaiseSize, playerSnapshots, board, potView.pots(), potView.pending(), awards,
                totalChipsAtStart);
    }

    private void verifyInvariants() {
        long chips = 0;
        HashSet<Card> knownCards = new HashSet<>();
        for (PlayerState player : players) {
            if (player.stack < 0 || player.streetCommitted < 0 || player.handCommitted < 0
                    || player.streetCommitted > player.handCommitted) {
                throw new IllegalStateException("negative or inconsistent player ledger");
            }
            chips = checkedAdd(chips, checkedAdd(player.stack, player.handCommitted));
            if (player.holeCards.size() != 2) throw new IllegalStateException("each player must have two hole cards");
            for (Card card : player.holeCards) if (!knownCards.add(card)) throw new IllegalStateException("duplicate dealt card");
        }
        for (Card card : board) if (!knownCards.add(card)) throw new IllegalStateException("duplicate board card");
        for (Card card : burns) if (!knownCards.add(card)) throw new IllegalStateException("duplicate burn card");

        if (phase == HandPhase.COMPLETE) {
            if (completionReason == null || actorSeat != null || round.currentBet != 0) {
                throw new IllegalStateException("invalid completed-hand state");
            }
            for (PlayerState player : players) if (player.handCommitted != 0) throw new IllegalStateException("complete hand has commitments");
            long stackTotal = 0;
            for (PlayerState player : players) stackTotal = checkedAdd(stackTotal, player.stack);
            if (stackTotal != totalChipsAtStart) throw new IllegalStateException("completed hand lost chips");
            return;
        }

        if (completionReason != null) throw new IllegalStateException("active hand has completion reason");
        int expectedBoard = switch (phase) {
            case PREFLOP -> 0;
            case FLOP -> 3;
            case TURN -> 4;
            case RIVER -> 5;
            case COMPLETE -> throw new IllegalStateException("unreachable phase");
        };
        if (board.size() != expectedBoard) throw new IllegalStateException("board size does not match phase");
        if (!settling && phase == HandPhase.PREFLOP && round.currentBet < config.bigBlind()) {
            throw new IllegalStateException("preflop target fell below nominal big blind");
        }
        if (!settling && phase != HandPhase.PREFLOP && round.currentBet != maxStreetCommitment()) {
            throw new IllegalStateException("postflop target does not match street commitments");
        }
        if (actorSeat != null) {
            PlayerState actor = playerAtSeat(actorSeat);
            if (actor == null || actor.folded || actor.allIn()) throw new IllegalStateException("invalid acting seat");
        }
        if (chips != totalChipsAtStart) throw new IllegalStateException("active hand lost chips");

        PotSettlement.View potView = PotSettlement.activeView(players);
        long represented = 0;
        for (Pot pot : potView.pots()) represented = checkedAdd(represented, pot.amount());
        if (potView.pending().isPresent()) represented = checkedAdd(represented, potView.pending().get().amount());
        long committed = 0;
        for (PlayerState player : players) committed = checkedAdd(committed, player.handCommitted);
        if (represented != committed) throw new IllegalStateException("pots do not reconcile with commitments");
    }

    private static List<Integer> occupiedSeats(List<PlayerState> players) {
        ArrayList<Integer> occupied = new ArrayList<>(players.size());
        for (PlayerState player : players) occupied.add(player.seat);
        occupied.sort(Integer::compare);
        return List.copyOf(occupied);
    }

    private List<Integer> occupiedSeats() {
        return occupiedSeats(players);
    }

    private PlayerState playerAtSeat(int seat) {
        for (PlayerState player : players) if (player.seat == seat) return player;
        return null;
    }

    private PlayerState playerById(PlayerId id) {
        for (PlayerState player : players) if (player.id.equals(id)) return player;
        return null;
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("chip arithmetic overflow", overflow);
        }
    }

    private static long checkedSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("chip arithmetic overflow", overflow);
        }
    }

    private static void reject(ActionRejectionReason reason) {
        throw new ActionRejectedException(reason);
    }
}
