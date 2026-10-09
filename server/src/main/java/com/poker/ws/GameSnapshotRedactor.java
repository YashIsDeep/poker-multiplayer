package com.poker.ws;

import com.poker.engine.cards.Card;
import com.poker.engine.cards.HandEvaluator;
import com.poker.engine.game.*;
import com.poker.room.*;
import com.poker.ws.protocol.GameView;
import java.util.*;
import org.springframework.stereotype.Component;

/** The sole allow-list boundary from trusted game state to viewer-safe JSON. */
@Component
public final class GameSnapshotRedactor {
    public GameView redact(RoomState room, UUID viewerId) {
        GameState game = room.game();
        if (game.seats().isEmpty()) return null;
        HandSnapshot hand = game.presentedHand();
        Integer viewerSeat = room.member(viewerId) == null ? null : room.member(viewerId).seat();
        boolean showdown = hand != null && hand.phase() == HandPhase.COMPLETE
                && hand.completionReason().orElse(null) == CompletionReason.SHOWDOWN;
        Map<Integer, PlayerSnapshot> players = new HashMap<>();
        if (hand != null) for (PlayerSnapshot p : hand.players()) players.put(p.seat(), p);
        Map<Integer, String> positions = pokerPositions(hand);
        List<GameView.GameSeatView> seatViews = new ArrayList<>();
        for (TableSeatState tableSeat : game.seats()) {
            PlayerSnapshot p = players.get(tableSeat.seat());
            // A completed hand retains its original owners while seats can already
            // change hands. Never attach historical state to a new occupant.
            if (p != null && !p.id().value().equals(tableSeat.userId().toString())) p = null;
            long stack = p == null ? tableSeat.stack() : p.stack();
            long committed = p == null ? 0 : p.streetCommitted();
            String status = tableSeat.status().name();
            if (p != null) status = p.folded() ? "FOLDED" : p.allIn() ? "ALL_IN" : status;
            boolean reveal = p != null && ((tableSeat.userId().equals(viewerId)
                    && p.id().value().equals(viewerId.toString())) || (showdown && !p.folded()));
            List<String> cards = reveal ? p.holeCards().stream().map(Card::toString).toList() : null;
            String rank = null;
            List<String> bestHandCards = List.of();
            if (showdown && p != null && !p.folded() && hand.board().size() == 5) {
                // Put the shared board first so equal-ranked subsets prefer
                // showing the common cards that explain both winning hands.
                ArrayList<Card> seven = new ArrayList<>(hand.board()); seven.addAll(p.holeCards());
                rank = HandEvaluator.evaluate7(seven).describe();
                bestHandCards = HandEvaluator.bestFive(seven).stream().map(Card::toString).toList();
            }
            List<GameView.LastActionView> actions = p == null ? List.of() : game.roundActions()
                    .getOrDefault(tableSeat.seat(), List.of()).stream()
                    .map(GameSnapshotRedactor::actionView).toList();
            GameView.LastActionView last = actions.isEmpty() ? null : actions.get(actions.size() - 1);
            seatViews.add(new GameView.GameSeatView(tableSeat.seat(), tableSeat.userId(), stack, status,
                    committed, cards, last, actions, p == null ? null : positions.get(tableSeat.seat()), rank,
                    bestHandCards, tableSeat.rebuyHandsRemaining(), tableSeat.rebought(), tableSeat.rebuyCount()));
        }

        LegalActions legal = game.legalActions();
        boolean viewerCanAct = viewerId != null && legal != null
                && legal.playerId().value().equals(viewerId.toString());
        GameView.LegalActionsView legalView = viewerCanAct ? new GameView.LegalActionsView(
                legal.canFold(), legal.canCheck(), legal.canCall(), legal.callAmount(), legal.toCall(),
                legal.canRaise(), legal.minimumRaiseTo(), legal.maximumRaiseTo()) : null;
        TableSeatState own = viewerSeat == null ? null : game.seats().stream().filter(s -> s.seat() == viewerSeat).findFirst().orElse(null);
        Long rebuyAmount = own != null && own.stack() == 0 && room.config().rebuysEnabled()
                ? room.config().rebuyStack() : null;
        Integer rebuyHandsRemaining = own != null && own.stack() == 0 ? own.rebuyHandsRemaining() : null;

        List<GameView.PotView> pots = hand == null ? List.of() : hand.pots().stream().map(p -> new GameView.PotView(
                p.amount(), p.eligible().stream().map(id -> seatOf(players, id)).filter(Objects::nonNull).toList())).toList();
        List<GameView.AwardView> awards = hand == null ? List.of() : hand.awards().stream().map(a -> new GameView.AwardView(
                a.potIndex(), a.potAmount(), a.winnerAwards().stream().map(w -> new GameView.WinnerView(w.seat(), w.amount())).toList())).toList();
        long minRaise = legalView == null ? 0 : legalView.minRaiseTo();
        long maxRaise = legalView == null ? 0 : legalView.maxRaiseTo();
        long toCall = legalView == null ? 0 : legalView.toCall();
        return new GameView(game.tableStatus().name(), game.handId(), hand == null ? null : hand.phase().name(),
                hand == null ? null : hand.completionReason().map(Enum::name).orElse(null), game.actionSeq(),
                hand == null || hand.actingSeat().isEmpty() ? null : hand.actingSeat().getAsInt(),
                game.deadline() == null ? null : game.deadline().toEpochMilli(),
                hand == null ? null : hand.buttonSeat(), hand == null ? null : hand.smallBlindSeat(),
                hand == null ? null : hand.bigBlindSeat(), hand == null ? List.of() : hand.board().stream().map(Card::toString).toList(),
                hand == null ? 0 : hand.currentBet(), potTotal(hand), pots, awards, seatViews,
                new GameView.ViewerGameView(viewerSeat != null, viewerSeat, viewerCanAct,
                        rebuyAmount, rebuyHandsRemaining, legalView),
                minRaise, maxRaise, toCall);
    }

    private static Integer seatOf(Map<Integer, PlayerSnapshot> players, PlayerId id) {
        return players.values().stream().filter(p -> p.id().equals(id)).map(PlayerSnapshot::seat).findFirst().orElse(null);
    }

    private static long potTotal(HandSnapshot hand) {
        if (hand == null) return 0;
        long total = 0;
        for (Pot pot : hand.pots()) total = Math.addExact(total, pot.amount());
        if (hand.pendingUnmatched().isPresent()) {
            total = Math.addExact(total, hand.pendingUnmatched().orElseThrow().amount());
        }
        return total;
    }

    private static GameView.LastActionView actionView(HandEvent.PlayerActed acted) {
        return new GameView.LastActionView(actionName(acted.action()), actionAmount(acted));
    }

    /** Labels active hand seats clockwise from the button using standard ring-game terms. */
    private static Map<Integer, String> pokerPositions(HandSnapshot hand) {
        if (hand == null) return Map.of();
        List<Integer> occupied = hand.players().stream().map(PlayerSnapshot::seat).sorted().toList();
        int count = occupied.size();
        List<String> labels = switch (count) {
            case 2 -> List.of("BN/SB", "BB");
            case 3 -> List.of("BN", "SB", "BB");
            case 4 -> List.of("BN", "SB", "BB", "CO");
            case 5 -> List.of("BN", "SB", "BB", "UTG", "CO");
            case 6 -> List.of("BN", "SB", "BB", "UTG", "MP", "CO");
            case 7 -> List.of("BN", "SB", "BB", "UTG", "MP", "HJ", "CO");
            case 8 -> List.of("BN", "SB", "BB", "UTG", "UTG+1", "MP", "HJ", "CO");
            case 9 -> List.of("BN", "SB", "BB", "UTG", "UTG+1", "MP", "LJ", "HJ", "CO");
            default -> throw new IllegalStateException("unsupported hand size: " + count);
        };
        int buttonIndex = occupied.indexOf(hand.buttonSeat());
        if (buttonIndex < 0) throw new IllegalStateException("button is not an occupied seat");
        Map<Integer, String> result = new HashMap<>();
        for (int offset = 0; offset < count; offset++) {
            result.put(occupied.get((buttonIndex + offset) % count), labels.get(offset));
        }
        return Map.copyOf(result);
    }

    private static String actionName(PlayerAction action) {
        if (action instanceof PlayerAction.Fold) return "FOLD";
        if (action instanceof PlayerAction.Check) return "CHECK";
        if (action instanceof PlayerAction.Call) return "CALL";
        return "RAISE_TO";
    }

    private static Long actionAmount(HandEvent.PlayerActed acted) {
        if (acted.action() instanceof PlayerAction.Call) {
            return acted.amountCommitted();
        }
        if (acted.action() instanceof PlayerAction.RaiseTo) {
            return acted.streetCommitted();
        }
        return null;
    }
}
