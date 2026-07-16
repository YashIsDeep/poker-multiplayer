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
import com.poker.engine.game.LegalActions;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Soak test for the archive pipeline: plays many random hands through the real
 * room stack and checks every completed hand lands in the compressed archive
 * exactly once with chips conserved. The generated files under
 * {@code target/sample-archive} are also handy sample data for the analytics
 * viewer ({@code npm run analytics}).
 */
class HandArchiveSimulationTest {
    private static final int TARGET_HANDS = 40;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final Random rng = new Random(20260716);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-12T18:00:00Z"));
    private final RoomManager rooms = new RoomManager(
            clock,
            new JoinCodeGenerator(new Random(7)),
            this::shuffledDeck);

    private Deck shuffledDeck() {
        List<Card> cards = new ArrayList<>(IntStream.range(0, 52).mapToObj(Card::new).toList());
        Collections.shuffle(cards, rng);
        return Deck.of(cards);
    }

    @Test
    void randomPlayoutsAllReachTheArchiveWithChipsConserved() throws Exception {
        Path dir = Path.of(System.getProperty(
                "handArchive.sampleDir", "target/sample-archive"));
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.delete(file);
            }
        }
        HandArchiveWriter writer = new HandArchiveWriter(
                new HandArchiveProperties(true, dir.toString()), JSON, clock);
        HandArchiveRecorder recorder = new HandArchiveRecorder(writer);

        String[] names = { "Alice", "Bob", "Chandra", "Dmitri" };
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < names.length; i++) users.add(UUID.randomUUID());

        RoomChange frame = rooms.create(
                new MemberProfile(users.get(0), names[0], null), "Friday game", 6, RoomConfig.DEFAULT);
        UUID roomId = frame.roomId();
        recorder.onChange(frame);
        for (int i = 1; i < names.length; i++) {
            recorder.onChange(rooms.join(
                    new MemberProfile(users.get(i), names[i], null), frame.room().joinCodeValue()));
        }
        for (int i = 0; i < names.length; i++) {
            frame = rooms.takeSeat(users.get(i), roomId, i, 2_000);
            recorder.onChange(frame);
        }

        int completedHands = 0;
        while (completedHands < TARGET_HANDS) {
            GameState game = frame.room().game();
            if (game.nextHandAt() == null) break; // fewer than two funded players remain
            clock.advanceTo(game.nextHandAt());
            frame = rooms.startHand(roomId, game.lifecycleSeq(), game.scheduleGeneration())
                    .orElseThrow();
            recorder.onChange(frame);
            frame = playOut(roomId, frame, recorder);
            completedHands++;

            game = frame.room().game();
            frame = rooms.nextHand(roomId, game.handId(), game.scheduleGeneration()).orElseThrow();
            recorder.onChange(frame);
            // Spread hands over several days so the writer exercises file rolling.
            clock.advance(Duration.ofMinutes(2 + rng.nextInt(18)));
            if (completedHands % 12 == 0) clock.advance(Duration.ofHours(20));
        }
        writer.close();

        assertThat(completedHands).isGreaterThanOrEqualTo(10);
        List<JsonNode> archived = readArchive(dir);
        assertThat(archived).hasSize(completedHands);
        assertThat(archived.stream().map(hand -> hand.get("handId").asLong()).distinct())
                .hasSize(completedHands);
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isGreaterThan(1); // rolled across days
        }
        for (JsonNode hand : archived) {
            long net = 0;
            for (JsonNode player : hand.get("players")) net += player.get("net").asLong();
            assertThat(net).as("chips conserved in hand %s", hand.get("handId")).isZero();
            assertThat(hand.get("events").size()).isGreaterThan(3);
        }
    }

    private RoomChange playOut(UUID roomId, RoomChange frame, HandArchiveRecorder recorder) {
        while (frame.room().game().presentedHand().phase() != HandPhase.COMPLETE) {
            GameState game = frame.room().game();
            LegalActions legal = game.legalActions();
            if (legal != null && game.presentedHand().actingSeat().isPresent()) {
                clock.advance(Duration.ofSeconds(1 + rng.nextInt(8)));
                UUID actor = UUID.fromString(legal.playerId().value());
                frame = act(roomId, actor, game.actionSeq(), legal);
            } else {
                frame = rooms.advancePresentation(
                        roomId, game.handId(), game.scheduleGeneration()).orElseThrow();
            }
            recorder.onChange(frame);
        }
        return frame;
    }

    private RoomChange act(UUID roomId, UUID actor, long actionSeq, LegalActions legal) {
        int roll = rng.nextInt(100);
        if (legal.canRaise() && roll < 18) {
            long raiseTo = Math.min(
                    legal.maximumRaiseTo(),
                    legal.minimumRaiseTo() + (rng.nextInt(3) == 0 ? legal.minimumRaiseTo() : 0));
            return rooms.act(actor, roomId, actionSeq, GameAction.RAISE_TO, raiseTo);
        }
        if (legal.canCheck()) return rooms.act(actor, roomId, actionSeq, GameAction.CHECK, null);
        if (legal.canCall() && roll < 80) {
            return rooms.act(actor, roomId, actionSeq, GameAction.CALL, null);
        }
        if (legal.canFold()) return rooms.act(actor, roomId, actionSeq, GameAction.FOLD, null);
        return rooms.act(actor, roomId, actionSeq, GameAction.CALL, null);
    }

    private static List<JsonNode> readArchive(Path dir) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                byte[] decompressed;
                try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(file))) {
                    decompressed = gzip.readAllBytes();
                }
                for (String line : new String(decompressed, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    if (line.isBlank()) continue;
                    try {
                        records.add(JSON.readTree(line));
                    } catch (IOException ex) {
                        throw new IllegalStateException("invalid archive line: " + line, ex);
                    }
                }
            }
        }
        return records;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        private void advanceTo(Instant target) {
            if (target.isAfter(now)) now = target;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
