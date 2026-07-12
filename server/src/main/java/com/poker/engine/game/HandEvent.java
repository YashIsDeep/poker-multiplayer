package com.poker.engine.game;

import com.poker.engine.cards.Card;
import java.util.List;
import java.util.Objects;

public sealed interface HandEvent permits HandEvent.BlindPosted, HandEvent.HoleCardDealt,
        HandEvent.PlayerActed, HandEvent.UncalledBetRefunded, HandEvent.StreetDealt,
        HandEvent.PotAwarded, HandEvent.HandCompleted {

    record BlindPosted(PlayerId playerId, int seat, BlindType blindType, long amount, boolean allIn) implements HandEvent {
        public BlindPosted {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(blindType, "blindType");
            if (seat < 0 || amount <= 0) throw new IllegalArgumentException("invalid blind event");
        }
    }

    record HoleCardDealt(PlayerId playerId, int seat, int cardIndex, Card card) implements HandEvent {
        public HoleCardDealt {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(card, "card");
            if (seat < 0 || cardIndex < 0 || cardIndex > 1) throw new IllegalArgumentException("invalid hole-card event");
        }
    }

    record PlayerActed(PlayerId playerId, int seat, PlayerAction action, long amountCommitted,
                       long streetCommitted, long stackAfter) implements HandEvent {
        public PlayerActed {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(action, "action");
            if (seat < 0 || amountCommitted < 0 || streetCommitted < 0 || stackAfter < 0) {
                throw new IllegalArgumentException("invalid action event");
            }
        }
    }

    record UncalledBetRefunded(PlayerId playerId, int seat, long amount, long stackAfter) implements HandEvent {
        public UncalledBetRefunded {
            Objects.requireNonNull(playerId, "playerId");
            if (seat < 0 || amount <= 0 || stackAfter < 0) throw new IllegalArgumentException("invalid refund event");
        }
    }

    record StreetDealt(HandPhase phase, List<Card> cardsAdded, List<Card> boardAfter) implements HandEvent {
        public StreetDealt {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(cardsAdded, "cardsAdded");
            Objects.requireNonNull(boardAfter, "boardAfter");
            if (phase != HandPhase.FLOP && phase != HandPhase.TURN && phase != HandPhase.RIVER) {
                throw new IllegalArgumentException("street events require a postflop phase");
            }
            if (cardsAdded.stream().anyMatch(Objects::isNull) || boardAfter.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("street cards cannot be null");
            }
            cardsAdded = List.copyOf(cardsAdded);
            boardAfter = List.copyOf(boardAfter);
        }
    }

    record PotAwarded(int potIndex, long potAmount, List<WinnerAward> winnerAwards) implements HandEvent {
        public PotAwarded {
            if (potIndex < 0 || potAmount <= 0) throw new IllegalArgumentException("invalid pot-award event");
            Objects.requireNonNull(winnerAwards, "winnerAwards");
            if (winnerAwards.isEmpty() || winnerAwards.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("pot-award event requires winners");
            }
            winnerAwards = List.copyOf(winnerAwards);
        }
    }

    record HandCompleted(CompletionReason reason) implements HandEvent {
        public HandCompleted {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
