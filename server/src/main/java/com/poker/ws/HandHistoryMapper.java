package com.poker.ws;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.HandEvaluator;
import com.poker.engine.game.*;
import com.poker.room.HandHistory;
import com.poker.room.HandHistoryEvent;
import com.poker.room.MemberState;
import com.poker.room.RoomState;
import com.poker.ws.protocol.HandHistoryView;
import java.util.*;
import org.springframework.stereotype.Component;

/** Redacts the trusted engine audit trail into a viewer-specific hand history. */
@Component
public final class HandHistoryMapper {
    public List<HandHistoryView> forViewer(RoomState room, UUID viewerId) {
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(viewerId, "viewerId");
        Map<UUID, String> names = new HashMap<>();
        for (MemberState member : room.members()) names.put(member.userId(), member.displayName());
        return room.game().handHistories().stream()
                .map(history -> hand(history, viewerId, names))
                .toList();
    }

    private HandHistoryView hand(
            HandHistory history,
            UUID viewerId,
            Map<UUID, String> names) {
        HandSnapshot snapshot = history.latestSnapshot();
        boolean showdown = snapshot.phase() == HandPhase.COMPLETE
                && snapshot.completionReason().orElse(null) == CompletionReason.SHOWDOWN;
        Map<Integer, PlayerSnapshot> snapshots = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) snapshots.put(player.seat(), player);
        Map<Integer, Long> winnings = new HashMap<>();
        for (PotAward award : snapshot.awards()) {
            for (WinnerAward winner : award.winnerAwards()) {
                winnings.merge(winner.seat(), winner.amount(), Math::addExact);
            }
        }

        List<HandHistoryView.PlayerView> players = history.players().stream().map(player -> {
            PlayerSnapshot latest = snapshots.get(player.seat());
            if (latest == null) throw new IllegalStateException("history lost a player snapshot");
            boolean reveal = player.userId().equals(viewerId) || (showdown && !latest.folded());
            List<String> cards = reveal
                    ? latest.holeCards().stream().map(Card::toString).toList() : null;
            String rank = reveal && showdown && !latest.folded() && snapshot.board().size() == 5
                    ? finalRank(latest, snapshot.board()) : null;
            long endingStack = latest.stack();
            return new HandHistoryView.PlayerView(
                    player.seat(), player.userId(),
                    names.getOrDefault(player.userId(), "Seat " + (player.seat() + 1)),
                    player.startingStack(), endingStack,
                    Math.subtractExact(endingStack, player.startingStack()),
                    winnings.getOrDefault(player.seat(), 0L), latest.folded(), cards, rank);
        }).toList();

        List<HandHistoryView.EventView> events = history.events().stream()
                .map(this::event)
                .filter(Objects::nonNull)
                .toList();
        return new HandHistoryView(
                history.handId(), history.startedAt(), history.completedAt(), snapshot.phase().name(),
                snapshot.completionReason().map(Enum::name).orElse(null),
                snapshot.buttonSeat(), snapshot.smallBlindSeat(), snapshot.bigBlindSeat(),
                history.smallBlind(), history.bigBlind(),
                snapshot.board().stream().map(Card::toString).toList(), players, events);
    }

    private HandHistoryView.EventView event(HandHistoryEvent historyEvent) {
        HandEvent event = historyEvent.event();
        if (event instanceof HandEvent.HoleCardDealt) return null;
        Integer seat = null;
        String action = null;
        Long amount = null;
        Long total = null;
        Long stackAfter = null;
        List<String> cards = List.of();
        Integer potIndex = null;
        Long potAmount = null;
        List<HandHistoryView.WinnerView> winners = List.of();
        String type;

        if (event instanceof HandEvent.BlindPosted blind) {
            type = "BLIND_POSTED";
            seat = blind.seat();
            action = blind.blindType().name();
            amount = blind.amount();
        } else if (event instanceof HandEvent.PlayerActed acted) {
            type = "PLAYER_ACTED";
            seat = acted.seat();
            action = actionName(acted.action());
            amount = acted.amountCommitted();
            total = acted.streetCommitted();
            stackAfter = acted.stackAfter();
        } else if (event instanceof HandEvent.UncalledBetRefunded refund) {
            type = "UNCALLED_BET_REFUNDED";
            seat = refund.seat();
            amount = refund.amount();
            stackAfter = refund.stackAfter();
        } else if (event instanceof HandEvent.StreetDealt dealt) {
            type = "STREET_DEALT";
            cards = dealt.cardsAdded().stream().map(Card::toString).toList();
        } else if (event instanceof HandEvent.PotAwarded awarded) {
            type = "POT_AWARDED";
            potIndex = awarded.potIndex();
            potAmount = awarded.potAmount();
            winners = awarded.winnerAwards().stream()
                    .map(winner -> new HandHistoryView.WinnerView(winner.seat(), winner.amount()))
                    .toList();
        } else if (event instanceof HandEvent.HandCompleted completed) {
            type = "HAND_COMPLETED";
            action = completed.reason().name();
        } else {
            throw new IllegalStateException("unknown hand event: " + event.getClass().getName());
        }

        return new HandHistoryView.EventView(
                historyEvent.sequence(), historyEvent.occurredAt(), historyEvent.phase().name(), type,
                seat, action, amount, total, stackAfter, cards, potIndex, potAmount, winners);
    }

    private static String actionName(PlayerAction action) {
        if (action instanceof PlayerAction.Fold) return "FOLD";
        if (action instanceof PlayerAction.Check) return "CHECK";
        if (action instanceof PlayerAction.Call) return "CALL";
        if (action instanceof PlayerAction.RaiseTo) return "RAISE_TO";
        throw new IllegalStateException("unknown player action");
    }

    private static String finalRank(PlayerSnapshot player, List<Card> board) {
        ArrayList<Card> seven = new ArrayList<>(player.holeCards());
        seven.addAll(board);
        return HandEvaluator.evaluate7(seven).describe();
    }
}
