package com.poker.analytics;

import com.poker.engine.cards.Card;
import com.poker.engine.game.BlindType;
import com.poker.engine.game.HandEvent;
import com.poker.engine.game.HandSnapshot;
import com.poker.engine.game.PlayerAction;
import com.poker.engine.game.PlayerSnapshot;
import com.poker.engine.game.PotAward;
import com.poker.engine.game.WinnerAward;
import com.poker.room.HandHistory;
import com.poker.room.HandHistoryEvent;
import com.poker.room.MemberState;
import com.poker.room.RoomState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps a completed in-memory {@link HandHistory} to the archive schema. */
final class HandArchiveMapper {

    private HandArchiveMapper() {
    }

    static HandArchiveRecord map(RoomState room, HandHistory history) {
        if (history.completedAt() == null) {
            throw new IllegalArgumentException("only completed hands can be archived");
        }
        HandSnapshot latest = history.latestSnapshot();
        Map<UUID, String> names = new HashMap<>();
        for (MemberState member : room.members()) {
            names.put(member.userId(), member.displayName());
        }
        Map<Integer, PlayerSnapshot> finalBySeat = new HashMap<>();
        for (PlayerSnapshot player : latest.players()) {
            finalBySeat.put(player.seat(), player);
        }
        Map<UUID, Long> wonByUser = new HashMap<>();
        for (PotAward award : latest.awards()) {
            for (WinnerAward winner : award.winnerAwards()) {
                wonByUser.merge(userId(winner.playerId().value()), winner.amount(), Long::sum);
            }
        }

        List<HandArchiveRecord.Player> players = history.players().stream()
                .map(player -> {
                    PlayerSnapshot fin = finalBySeat.get(player.seat());
                    long endingStack = fin == null ? player.startingStack() : fin.stack();
                    return new HandArchiveRecord.Player(
                            player.userId(),
                            names.getOrDefault(player.userId(), null),
                            player.seat(),
                            player.startingStack(),
                            endingStack,
                            endingStack - player.startingStack(),
                            fin == null ? 0 : fin.handCommitted(),
                            fin == null ? List.of() : cards(fin.holeCards()),
                            fin != null && fin.folded(),
                            fin != null && fin.allIn(),
                            wonByUser.getOrDefault(player.userId(), 0L));
                })
                .toList();

        List<HandArchiveRecord.Award> awards = latest.awards().stream()
                .map(award -> new HandArchiveRecord.Award(
                        award.potIndex(), award.potAmount(), winners(award.winnerAwards())))
                .toList();
        long potAwarded = latest.awards().stream().mapToLong(PotAward::potAmount).sum();

        List<HandArchiveRecord.Event> events = new ArrayList<>(history.events().size());
        for (HandHistoryEvent event : history.events()) {
            events.add(map(event));
        }

        return new HandArchiveRecord(
                HandArchiveRecord.SCHEMA_VERSION,
                room.roomId(),
                room.name(),
                history.handId(),
                history.startedAt(),
                history.completedAt(),
                history.smallBlind(),
                history.bigBlind(),
                latest.completionReason().map(Enum::name).orElse(null),
                latest.buttonSeat(),
                latest.smallBlindSeat(),
                latest.bigBlindSeat(),
                cards(latest.board()),
                potAwarded,
                players,
                awards,
                events, room.mode(), players.size());
    }

    private static HandArchiveRecord.Event map(HandHistoryEvent history) {
        long seq = history.sequence();
        var at = history.occurredAt();
        String phase = history.phase().name();
        return switch (history.event()) {
            case HandEvent.BlindPosted e -> new HandArchiveRecord.Event(
                    seq, at, phase, "BLIND_POSTED", e.seat(), userId(e.playerId().value()),
                    e.blindType() == BlindType.SMALL_BLIND ? "SMALL_BLIND" : "BIG_BLIND",
                    e.amount(), null, null, e.allIn() ? true : null, null, null, null, null, null, null);
            case HandEvent.HoleCardDealt e -> new HandArchiveRecord.Event(
                    seq, at, phase, "HOLE_CARD_DEALT", e.seat(), userId(e.playerId().value()),
                    null, null, null, null, null, List.of(e.card().toString()),
                    null, null, null, null, null);
            case HandEvent.PlayerActed e -> new HandArchiveRecord.Event(
                    seq, at, phase, "PLAYER_ACTED", e.seat(), userId(e.playerId().value()),
                    actionName(e.action()), e.amountCommitted(),
                    e.action() instanceof PlayerAction.RaiseTo raise ? raise.amount() : null,
                    e.stackAfter(), null, null, null, null, null, null, null);
            case HandEvent.UncalledBetRefunded e -> new HandArchiveRecord.Event(
                    seq, at, phase, "UNCALLED_BET_REFUNDED", e.seat(), userId(e.playerId().value()),
                    null, e.amount(), null, e.stackAfter(), null, null, null, null, null, null, null);
            case HandEvent.StreetDealt e -> new HandArchiveRecord.Event(
                    seq, at, phase, "STREET_DEALT", null, null,
                    null, null, null, null, null, cards(e.cardsAdded()), cards(e.boardAfter()),
                    null, null, null, null);
            case HandEvent.PotAwarded e -> new HandArchiveRecord.Event(
                    seq, at, phase, "POT_AWARDED", null, null,
                    null, null, null, null, null, null, null,
                    e.potIndex(), e.potAmount(), winners(e.winnerAwards()), null);
            case HandEvent.HandCompleted e -> new HandArchiveRecord.Event(
                    seq, at, phase, "HAND_COMPLETED", null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    e.reason().name());
        };
    }

    private static List<HandArchiveRecord.Winner> winners(List<WinnerAward> awards) {
        return awards.stream()
                .map(winner -> new HandArchiveRecord.Winner(
                        userId(winner.playerId().value()), winner.seat(), winner.amount()))
                .toList();
    }

    private static String actionName(PlayerAction action) {
        return switch (action) {
            case PlayerAction.Fold ignored -> "FOLD";
            case PlayerAction.Check ignored -> "CHECK";
            case PlayerAction.Call ignored -> "CALL";
            case PlayerAction.RaiseTo ignored -> "RAISE_TO";
        };
    }

    private static List<String> cards(List<Card> cards) {
        return cards.stream().map(Card::toString).toList();
    }

    /** Engine PlayerId values are room member UUIDs rendered as strings. */
    private static UUID userId(String playerId) {
        return UUID.fromString(playerId);
    }
}
