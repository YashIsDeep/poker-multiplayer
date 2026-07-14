package com.poker.ws.protocol;

import java.util.List;
import java.util.UUID;

public record GameView(
        String tableStatus, long handId, String phase, String completionReason,
        long actionSeq, Integer actingSeat, Long deadline, Integer buttonSeat,
        Integer smallBlindSeat, Integer bigBlindSeat, List<String> board,
        long currentBet, long potTotal, List<PotView> pots, List<AwardView> awards,
        List<GameSeatView> seats, ViewerGameView viewer,
        long minRaiseTo, long maxRaiseTo, long toCall) {
    public GameView { board = List.copyOf(board); pots = List.copyOf(pots); awards = List.copyOf(awards); seats = List.copyOf(seats); }

    public record PotView(long amount, List<Integer> eligibleSeats) {
        public PotView { eligibleSeats = List.copyOf(eligibleSeats); }
    }
    public record WinnerView(int seat, long amount) {}
    public record AwardView(int potIndex, long potAmount, List<WinnerView> winners) {
        public AwardView { winners = List.copyOf(winners); }
    }
    public record LastActionView(String action, Long amount) {}
    public record GameSeatView(int seat, UUID userId, long stack, String seatStatus, long committed,
                               List<String> holeCards, LastActionView lastAction,
                               List<LastActionView> actions, String position, String finalHandRank,
                               List<String> bestHandCards, int rebuyHandsRemaining,
                               boolean rebought, int rebuyCount) {
        /** Keeps the pre-best-five constructor usable for older protocol fixtures. */
        public GameSeatView(int seat, UUID userId, long stack, String seatStatus, long committed,
                            List<String> holeCards, LastActionView lastAction,
                            List<LastActionView> actions, String position, String finalHandRank,
                            int rebuyHandsRemaining, boolean rebought, int rebuyCount) {
            this(seat, userId, stack, seatStatus, committed, holeCards, lastAction, actions,
                    position, finalHandRank, List.of(), rebuyHandsRemaining, rebought, rebuyCount);
        }

        /** Keeps the pre-count constructor usable for older protocol fixtures. */
        public GameSeatView(int seat, UUID userId, long stack, String seatStatus, long committed,
                            List<String> holeCards, LastActionView lastAction,
                            List<LastActionView> actions, String position, String finalHandRank,
                            int rebuyHandsRemaining, boolean rebought) {
            this(seat, userId, stack, seatStatus, committed, holeCards, lastAction, actions,
                    position, finalHandRank, List.of(), rebuyHandsRemaining, rebought, rebought ? 1 : 0);
        }

        public GameSeatView {
            holeCards = holeCards == null ? null : List.copyOf(holeCards);
            actions = List.copyOf(actions);
            bestHandCards = bestHandCards == null ? List.of() : List.copyOf(bestHandCards);
        }
    }
    public record LegalActionsView(boolean canFold, boolean canCheck, boolean canCall, long callAmount,
                                   long toCall, boolean canRaise, long minRaiseTo, long maxRaiseTo) {}
    public record ViewerGameView(boolean seated, Integer seat, boolean canAct, Long rebuyAmount,
                                 Integer rebuyHandsRemaining, LegalActionsView legalActions) {}
}
