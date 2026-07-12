package com.poker.ws.protocol;

import java.util.List;
import java.util.UUID;

public record GameView(
        String tableStatus, long handId, String phase, String completionReason,
        long actionSeq, Integer actingSeat, Long deadline, Integer buttonSeat,
        Integer smallBlindSeat, Integer bigBlindSeat, List<String> board,
        long currentBet, List<PotView> pots, List<AwardView> awards,
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
                               List<String> holeCards, LastActionView lastAction, String finalHandRank) {
        public GameSeatView { holeCards = holeCards == null ? null : List.copyOf(holeCards); }
    }
    public record LegalActionsView(boolean canFold, boolean canCheck, boolean canCall, long callAmount,
                                   long toCall, boolean canRaise, long minRaiseTo, long maxRaiseTo) {}
    public record ViewerGameView(boolean seated, Integer seat, boolean canAct, Long rebuyMinimum,
                                 Long rebuyMaximum, LegalActionsView legalActions) {}
}
