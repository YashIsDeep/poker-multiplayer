package com.poker.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stable, self-contained analytics schema for one completed hand. It is
 * written to the local compressed archive independently of the database, so
 * every field a reader needs (including display names captured at completion
 * time) is embedded here rather than resolved through other storage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandArchiveRecord(
        int schemaVersion,
        UUID roomId,
        String roomName,
        long handId,
        Instant startedAt,
        Instant completedAt,
        long smallBlind,
        long bigBlind,
        String completionReason,
        int buttonSeat,
        int smallBlindSeat,
        int bigBlindSeat,
        List<String> board,
        long potAwarded,
        List<Player> players,
        List<Award> awards,
        List<Event> events, String mode, int participantCount) {

    public static final int SCHEMA_VERSION = 2;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Player(
            UUID userId,
            String displayName,
            int seat,
            long startingStack,
            long endingStack,
            long net,
            long committed,
            List<String> holeCards,
            boolean folded,
            boolean allIn,
            long won) {}

    public record Award(int potIndex, long potAmount, List<Winner> winners) {}

    public record Winner(UUID userId, int seat, long amount) {}

    /**
     * One engine event flattened into a single shape; fields that do not apply
     * to the event type are null and omitted from the serialized form.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            long sequence,
            Instant occurredAt,
            String phase,
            String type,
            Integer seat,
            UUID userId,
            String action,
            Long amount,
            Long raiseTo,
            Long stackAfter,
            Boolean allIn,
            List<String> cards,
            List<String> board,
            Integer potIndex,
            Long potAmount,
            List<Winner> winners,
            String reason) {}
}
