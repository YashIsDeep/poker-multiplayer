package com.poker.admin;

import com.poker.engine.cards.Card;
import com.poker.engine.game.BlindType;
import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandSnapshot;
import com.poker.engine.game.LegalActions;
import com.poker.engine.game.PlayerAction;
import com.poker.engine.game.PlayerId;
import com.poker.engine.game.PlayerSnapshot;
import com.poker.engine.game.Pot;
import com.poker.engine.game.PotAward;
import com.poker.engine.game.WinnerAward;
import com.poker.room.GameState;
import com.poker.room.HandHistory;
import com.poker.room.HandHistoryEvent;
import com.poker.room.MemberState;
import com.poker.room.RoomState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps in-memory hand state to admin drilldown views. These views are the one
 * place the server intentionally reveals full dealt cards; they are reachable
 * only through the loopback-guarded admin API.
 */
final class AdminHandViews {

    private AdminHandViews() {
    }

    static LiveHandView liveHand(RoomState room) {
        GameState game = room.game();
        HandSnapshot hand = game.presentedHand();
        if (hand == null) {
            return new LiveHandView(
                    false, game.tableStatus().name(), game.handId(), null, null,
                    null, null, null, null, null, game.nextHandAt(),
                    0, 0, 0, 0, List.of(), List.of(), null, List.of(), List.of(), null);
        }

        Map<String, String> names = namesByPlayerId(room);
        Map<String, Integer> seatsByPlayer = hand.players().stream()
                .collect(Collectors.toMap(player -> player.id().value(), PlayerSnapshot::seat));

        long potTotal = hand.pots().stream().mapToLong(Pot::amount).sum()
                + hand.pendingUnmatched().map(pending -> pending.amount()).orElse(0L);

        List<PotView> pots = new ArrayList<>();
        for (int index = 0; index < hand.pots().size(); index++) {
            Pot pot = hand.pots().get(index);
            pots.add(new PotView(index, pot.amount(), pot.cap(),
                    seatList(pot.eligible(), seatsByPlayer)));
        }

        List<HandPlayerView> players = hand.players().stream()
                .map(player -> new HandPlayerView(
                        player.seat(),
                        player.id().value(),
                        names.getOrDefault(player.id().value(), "-"),
                        cards(player.holeCards()),
                        player.stack(),
                        player.streetCommitted(),
                        player.handCommitted(),
                        player.folded(),
                        player.allIn(),
                        role(player.seat(), hand)))
                .toList();

        LegalActions legal = game.legalActions();
        LegalActionsView legalView = legal == null ? null : new LegalActionsView(
                seatsByPlayer.get(legal.playerId().value()),
                legal.playerId().value(),
                legal.toCall(),
                legal.canFold(),
                legal.canCheck(),
                legal.canCall(),
                legal.callAmount(),
                legal.canRaise(),
                legal.minimumRaiseTo(),
                legal.maximumRaiseTo());

        return new LiveHandView(
                true,
                game.tableStatus().name(),
                game.handId(),
                hand.phase().name(),
                hand.completionReason().map(Enum::name).orElse(null),
                hand.buttonSeat(),
                hand.smallBlindSeat(),
                hand.bigBlindSeat(),
                hand.actingSeat().isPresent() ? hand.actingSeat().getAsInt() : null,
                game.deadline(),
                game.nextHandAt(),
                hand.currentBet(),
                hand.lastFullRaiseSize(),
                hand.totalChipsAtStart(),
                potTotal,
                cards(hand.board()),
                pots,
                hand.pendingUnmatched()
                        .map(pending -> new PendingView(pending.seat(), pending.amount()))
                        .orElse(null),
                awards(hand.awards(), names),
                players,
                legalView);
    }

    static List<HandSummaryView> handSummaries(RoomState room) {
        Map<String, String> names = namesByPlayerId(room);
        return room.game().handHistories().stream()
                .map(history -> summarize(history, names))
                .toList();
    }

    static HandDetailView handDetail(RoomState room, long handId) {
        Map<String, String> names = namesByPlayerId(room);
        HandHistory history = room.game().handHistories().stream()
                .filter(candidate -> candidate.handId() == handId)
                .findFirst()
                .orElse(null);
        if (history == null) return null;

        Map<Integer, String> namesBySeat = new HashMap<>();
        List<HandPlayerInfo> players = history.players().stream()
                .map(player -> {
                    String name = names.getOrDefault(player.userId().toString(), "-");
                    namesBySeat.put(player.seat(), name);
                    return new HandPlayerInfo(
                            player.seat(), player.userId(), name, player.startingStack());
                })
                .toList();

        List<EventView> events = history.events().stream()
                .map(event -> new EventView(
                        event.sequence(),
                        event.occurredAt(),
                        event.phase().name(),
                        describe(event.event(), namesBySeat)))
                .toList();

        HandSnapshot latest = history.latestSnapshot();
        return new HandDetailView(
                history.handId(),
                history.completedAt() == null,
                history.startedAt(),
                history.completedAt(),
                history.smallBlind(),
                history.bigBlind(),
                players,
                cards(latest.board()),
                awards(latest.awards(), names),
                events);
    }

    private static HandSummaryView summarize(HandHistory history, Map<String, String> names) {
        HandSnapshot latest = history.latestSnapshot();
        long potAwarded = latest.awards().stream().mapToLong(PotAward::potAmount).sum();
        String winners = latest.awards().stream()
                .flatMap(award -> award.winnerAwards().stream())
                .map(winner -> winnerLabel(winner, names))
                .collect(Collectors.joining(", "));
        return new HandSummaryView(
                history.handId(),
                history.completedAt() == null,
                history.startedAt(),
                history.completedAt(),
                history.smallBlind(),
                history.bigBlind(),
                history.players().size(),
                latest.phase().name(),
                latest.completionReason().map(Enum::name).orElse(null),
                cards(latest.board()),
                potAwarded,
                winners.isEmpty() ? null : winners);
    }

    private static List<AwardView> awards(List<PotAward> awards, Map<String, String> names) {
        return awards.stream()
                .map(award -> new AwardView(
                        award.potIndex(),
                        award.potAmount(),
                        award.winnerAwards().stream()
                                .map(winner -> new WinnerView(
                                        winner.seat(),
                                        names.getOrDefault(winner.playerId().value(), "-"),
                                        winner.amount()))
                                .toList()))
                .toList();
    }

    private static String winnerLabel(WinnerAward winner, Map<String, String> names) {
        String name = names.getOrDefault(winner.playerId().value(), null);
        String who = name == null ? "seat " + winner.seat() : name;
        return who + " +" + winner.amount();
    }

    private static String describe(HandEvent event, Map<Integer, String> namesBySeat) {
        return switch (event) {
            case HandEvent.BlindPosted e -> "%s posts %s %d%s".formatted(
                    seatLabel(e.seat(), namesBySeat),
                    e.blindType() == BlindType.SMALL_BLIND ? "small blind" : "big blind",
                    e.amount(),
                    e.allIn() ? " (all-in)" : "");
            case HandEvent.HoleCardDealt e -> "%s dealt hole card %d: %s".formatted(
                    seatLabel(e.seat(), namesBySeat), e.cardIndex() + 1, e.card());
            case HandEvent.PlayerActed e -> "%s %s (street %d, stack %d)".formatted(
                    seatLabel(e.seat(), namesBySeat),
                    actionLabel(e.action(), e.amountCommitted()),
                    e.streetCommitted(),
                    e.stackAfter());
            case HandEvent.UncalledBetRefunded e -> "%s refunded uncalled bet %d (stack %d)".formatted(
                    seatLabel(e.seat(), namesBySeat), e.amount(), e.stackAfter());
            case HandEvent.StreetDealt e -> "%s dealt: %s (board %s)".formatted(
                    e.phase().name(),
                    String.join(" ", cards(e.cardsAdded())),
                    String.join(" ", cards(e.boardAfter())));
            case HandEvent.PotAwarded e -> "pot %d (%d) awarded: %s".formatted(
                    e.potIndex(),
                    e.potAmount(),
                    e.winnerAwards().stream()
                            .map(winner -> seatLabel(winner.seat(), namesBySeat) + " +" + winner.amount())
                            .collect(Collectors.joining(", ")));
            case HandEvent.HandCompleted e -> "hand complete: " + e.reason().name();
        };
    }

    private static String actionLabel(PlayerAction action, long amountCommitted) {
        return switch (action) {
            case PlayerAction.Fold ignored -> "folds";
            case PlayerAction.Check ignored -> "checks";
            case PlayerAction.Call ignored -> "calls " + amountCommitted;
            case PlayerAction.RaiseTo raise -> "raises to " + raise.amount();
        };
    }

    private static String seatLabel(int seat, Map<Integer, String> namesBySeat) {
        String name = namesBySeat.get(seat);
        return name == null || "-".equals(name) ? "seat " + seat : "seat " + seat + " (" + name + ")";
    }

    private static String role(int seat, HandSnapshot hand) {
        List<String> roles = new ArrayList<>();
        if (seat == hand.buttonSeat()) roles.add("BTN");
        if (seat == hand.smallBlindSeat()) roles.add("SB");
        if (seat == hand.bigBlindSeat()) roles.add("BB");
        return roles.isEmpty() ? null : String.join(",", roles);
    }

    private static List<Integer> seatList(List<PlayerId> players, Map<String, Integer> seatsByPlayer) {
        return players.stream()
                .map(player -> seatsByPlayer.get(player.value()))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private static List<String> cards(List<Card> cards) {
        return cards.stream().map(Card::toString).toList();
    }

    /** Engine PlayerId values are room member UUIDs rendered as strings. */
    private static Map<String, String> namesByPlayerId(RoomState room) {
        Map<String, String> names = new HashMap<>();
        for (MemberState member : room.members()) {
            names.put(member.userId().toString(), member.displayName());
        }
        return names;
    }

    record LiveHandView(
            boolean active,
            String tableStatus,
            long handId,
            String phase,
            String completionReason,
            Integer buttonSeat,
            Integer smallBlindSeat,
            Integer bigBlindSeat,
            Integer actingSeat,
            Instant deadline,
            Instant nextHandAt,
            long currentBet,
            long lastFullRaiseSize,
            long totalChipsAtStart,
            long potTotal,
            List<String> board,
            List<PotView> pots,
            PendingView pendingUnmatched,
            List<AwardView> awards,
            List<HandPlayerView> players,
            LegalActionsView legalActions) {}

    record HandPlayerView(
            int seat,
            String userId,
            String displayName,
            List<String> holeCards,
            long stack,
            long streetCommitted,
            long handCommitted,
            boolean folded,
            boolean allIn,
            String role) {}

    record PotView(int index, long amount, long cap, List<Integer> eligibleSeats) {}

    record PendingView(int seat, long amount) {}

    record AwardView(int potIndex, long potAmount, List<WinnerView> winners) {}

    record WinnerView(int seat, String displayName, long amount) {}

    record LegalActionsView(
            Integer seat,
            String playerId,
            long toCall,
            boolean canFold,
            boolean canCheck,
            boolean canCall,
            long callAmount,
            boolean canRaise,
            long minimumRaiseTo,
            long maximumRaiseTo) {}

    record HandSummaryView(
            long handId,
            boolean inProgress,
            Instant startedAt,
            Instant completedAt,
            long smallBlind,
            long bigBlind,
            int playerCount,
            String phase,
            String completionReason,
            List<String> board,
            long potAwarded,
            String winners) {}

    record HandDetailView(
            long handId,
            boolean inProgress,
            Instant startedAt,
            Instant completedAt,
            long smallBlind,
            long bigBlind,
            List<HandPlayerInfo> players,
            List<String> finalBoard,
            List<AwardView> awards,
            List<EventView> events) {}

    record HandPlayerInfo(int seat, UUID userId, String displayName, long startingStack) {}

    record EventView(long sequence, Instant occurredAt, String phase, String description) {}
}
