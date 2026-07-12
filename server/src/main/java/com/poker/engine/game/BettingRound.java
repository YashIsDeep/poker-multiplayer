package com.poker.engine.game;

/** Mutable per-street bookkeeping owned by a single HoldemHand. */
final class BettingRound {
    long currentBet;
    long lastFullRaiseSize;

    void startPreflop(long nominalBigBlind) {
        currentBet = nominalBigBlind;
        lastFullRaiseSize = nominalBigBlind;
    }

    void startPostflop(long nominalBigBlind) {
        currentBet = 0;
        lastFullRaiseSize = nominalBigBlind;
    }
}
