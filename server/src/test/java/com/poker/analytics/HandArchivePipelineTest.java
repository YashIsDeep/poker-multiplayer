package com.poker.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.poker.engine.cards.Card;
import com.poker.engine.cards.Deck;
import com.poker.engine.game.HandPhase;
import com.poker.room.GameAction;
import com.poker.room.GameState;
import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomChange;
import com.poker.room.RoomConfig;
import com.poker.room.RoomManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandArchivePipelineTest {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-16T00:00:00Z"));
    private final RoomManager rooms = new RoomManager(
            clock,
            new JoinCodeGenerator(new java.util.Random(41)),
            () -> Deck.of(IntStream.range(0, 52).mapToObj(Card::new).toList()));

    @TempDir
    Path tempDir;

    @Test
    void archivesEachCompletedHandExactlyOnceWithFullDetail() throws Exception {
        HandArchiveWriter writer = new HandArchiveWriter(
                new HandArchiveProperties(true, tempDir.toString()), JSON, clock);
        HandArchiveRecorder recorder = new HandArchiveRecorder(writer);

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RoomChange frame = rooms.create(
                new MemberProfile(alice, "Alice", null), "Archive table", 2, RoomConfig.DEFAULT);
        recorder.onChange(frame);
        recorder.onChange(rooms.join(new MemberProfile(bob, "Bob", null), frame.room().joinCodeValue()));
        recorder.onChange(rooms.takeSeat(alice, frame.roomId(), 0, 500));
        frame = rooms.takeSeat(bob, frame.roomId(), 1, 500);
        recorder.onChange(frame);

        clock.advance(Duration.ofSeconds(2));
        GameState waiting = frame.room().game();
        frame = rooms.startHand(
                frame.roomId(), waiting.lifecycleSeq(), waiting.scheduleGeneration()).orElseThrow();
        recorder.onChange(frame);

        GameState game = frame.room().game();
        int actorSeat = game.presentedHand().actingSeat().orElseThrow();
        UUID actor = game.seats().stream()
                .filter(seat -> seat.seat() == actorSeat).findFirst().orElseThrow().userId();
        frame = rooms.act(actor, frame.roomId(), game.actionSeq(), GameAction.FOLD, null);
        recorder.onChange(frame);
        while (frame.room().game().presentedHand().phase() != HandPhase.COMPLETE) {
            game = frame.room().game();
            frame = rooms.advancePresentation(
                    frame.roomId(), game.handId(), game.scheduleGeneration()).orElseThrow();
            recorder.onChange(frame);
        }
        // A repeated publish of the same completed state must not archive again.
        recorder.onChange(frame);
        recorder.onChange(frame);
        // A no-op leave by a non-member publishes LEFT with a null room for a
        // live roomId; it must not reset the dedup mark and cause re-archiving.
        recorder.onChange(rooms.leave(UUID.randomUUID(), frame.roomId()));
        recorder.onChange(frame);

        List<JsonNode> records = awaitRecords(1);
        writer.close();
        assertThat(awaitRecords(1)).hasSize(1);

        JsonNode hand = records.getFirst();
        assertThat(hand.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(hand.get("roomId").asText()).isEqualTo(frame.roomId().toString());
        assertThat(hand.get("roomName").asText()).isEqualTo("Archive table");
        assertThat(hand.get("handId").asLong()).isEqualTo(1);
        assertThat(hand.get("completionReason").asText()).isEqualTo("FOLD_WIN");
        assertThat(hand.get("startedAt").asText()).isNotEmpty();
        assertThat(hand.get("completedAt").asText()).isNotEmpty();
        assertThat(hand.get("smallBlind").asLong()).isPositive();
        assertThat(hand.get("potAwarded").asLong()).isPositive();

        List<JsonNode> players = new ArrayList<>();
        hand.get("players").forEach(players::add);
        assertThat(players).hasSize(2);
        long netSum = 0;
        for (JsonNode player : players) {
            netSum += player.get("net").asLong();
            // The archive is a trusted local file: hole cards are unredacted.
            assertThat(player.get("holeCards")).hasSize(2);
            assertThat(player.get("displayName").asText()).isIn("Alice", "Bob");
        }
        assertThat(netSum).isZero();

        List<String> eventTypes = new ArrayList<>();
        hand.get("events").forEach(event -> eventTypes.add(event.get("type").asText()));
        assertThat(eventTypes)
                .contains("BLIND_POSTED", "HOLE_CARD_DEALT", "PLAYER_ACTED",
                        "POT_AWARDED", "HAND_COMPLETED");
    }

    @Test
    void appendedBatchesStayOneValidGzipStream() throws Exception {
        HandArchiveWriter writer = new HandArchiveWriter(
                new HandArchiveProperties(true, tempDir.toString()), JSON, clock);
        try {
            writer.append(sample(11));
            awaitRecords(1);
            writer.append(sample(12));
            List<JsonNode> records = awaitRecords(2);
            assertThat(records)
                    .extracting(node -> node.get("handId").asLong())
                    .containsExactly(11L, 12L);
        } finally {
            writer.close();
        }
    }

    private HandArchiveRecord sample(long handId) {
        UUID user = UUID.randomUUID();
        return new HandArchiveRecord(
                HandArchiveRecord.SCHEMA_VERSION, UUID.randomUUID(), "Sample", handId,
                clock.instant(), clock.instant(), 5, 10, "FOLD_WIN", 0, 0, 1,
                List.of(), 15,
                List.of(new HandArchiveRecord.Player(
                        user, "Solo", 0, 500, 515, 15, 10, List.of("As", "Kd"),
                        false, false, 15)),
                List.of(new HandArchiveRecord.Award(0, 15,
                        List.of(new HandArchiveRecord.Winner(user, 0, 15)))),
                List.of());
    }

    private List<JsonNode> awaitRecords(int expected) throws Exception {
        Path file = tempDir.resolve("hands-2026-07-16.jsonl.gz");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<JsonNode> records = List.of();
        while (System.nanoTime() < deadline) {
            records = Files.exists(file) ? readAll(file) : List.of();
            if (records.size() >= expected) return records;
            Thread.sleep(50);
        }
        return records;
    }

    private static List<JsonNode> readAll(Path file) throws IOException {
        // GZIPInputStream reads concatenated members as one stream, which is
        // exactly the shape the appending writer produces.
        byte[] decompressed;
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(file))) {
            decompressed = gzip.readAllBytes();
        }
        List<JsonNode> records = new ArrayList<>();
        for (String line : new String(decompressed, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) records.add(readTree(line));
        }
        return records;
    }

    private static JsonNode readTree(String line) {
        try {
            return JSON.readTree(line);
        } catch (IOException ex) {
            throw new IllegalStateException("invalid archive line: " + line, ex);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
