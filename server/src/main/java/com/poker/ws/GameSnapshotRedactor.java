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

        List<GameView.GameSeatView> seatViews = new ArrayList<>();
        for (TableSeatState tableSeat : game.seats()) {
            PlayerSnapshot p = players.get(tableSeat.seat());
            long stack = p == null ? tableSeat.stack() : p.stack();
            long committed = p == null ? 0 : p.streetCommitted();
            String status = tableSeat.status().name();
            if (p != null) status = p.folded() ? "FOLDED" : p.allIn() ? "ALL_IN" : status;
            boolean reveal = p != null && (Objects.equals(viewerSeat, p.seat()) || (showdown && !p.folded()));
            List<String> cards = reveal ? p.holeCards().stream().map(Card::toString).toList() : null;
            String rank = null;
            if (showdown && p != null && !p.folded() && hand.board().size() == 5) {
                ArrayList<Card> seven = new ArrayList<>(p.holeCards()); seven.addAll(hand.board());
                rank = HandEvaluator.evaluate7(seven).describe();
            }
            HandEvent.PlayerActed acted = game.lastActions().get(tableSeat.seat());
            GameView.LastActionView last = acted == null ? null : new GameView.LastActionView(actionName(acted.action()),
                    actionAmount(acted));
            seatViews.add(new GameView.GameSeatView(tableSeat.seat(), tableSeat.userId(), stack, status,
                    committed, cards, last, rank));
        }

        LegalActions legal = game.legalActions();
        boolean viewerCanAct = viewerId != null && legal != null
                && legal.playerId().value().equals(viewerId.toString());
        GameView.LegalActionsView legalView = viewerCanAct ? new GameView.LegalActionsView(
                legal.canFold(), legal.canCheck(), legal.canCall(), legal.callAmount(), legal.toCall(),
                legal.canRaise(), legal.minimumRaiseTo(), legal.maximumRaiseTo()) : null;
        TableSeatState own = viewerSeat == null ? null : game.seats().stream().filter(s -> s.seat() == viewerSeat).findFirst().orElse(null);
        Long rebuyMin = own != null && own.stack() == 0 ? room.config().minBuyIn() : null;
        Long rebuyMax = own != null && own.stack() == 0 ? room.config().maxBuyIn() : null;

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
                hand == null ? 0 : hand.currentBet(), pots, awards, seatViews,
                new GameView.ViewerGameView(viewerSeat != null, viewerSeat, viewerCanAct, rebuyMin, rebuyMax, legalView),
                minRaise, maxRaise, toCall);
    }

    private static Integer seatOf(Map<Integer, PlayerSnapshot> players, PlayerId id) {
        return players.values().stream().filter(p -> p.id().equals(id)).map(PlayerSnapshot::seat).findFirst().orElse(null);
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
