package com.poker.ranked;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RankedResultsTest {
    JdbcTemplate jdbc;
    RankedResults results;
    DataSourceTransactionManager manager;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ranked_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        results = new RankedResults(jdbc, manager);
        RankedFixtures.USERS.forEach(this::user);
    }

    void user(UUID id) {
        jdbc.update("INSERT INTO app_user (id, provider, provider_subject, display_name, avatar_url,"
                        + " created_at, last_login_at) VALUES (?, 'DEV', ?, 'Player', NULL, ?, ?)",
                id, id.toString(), Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC), Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC));
    }

    @Test void foldsSplitPotsSidePotsAndAllInsConserveChips() {
        for (String style : List.of("fold", "split", "allin")) {
            var hand = RankedFixtures.hand(1, style, 100, 200, 300);
            var earnings = RankedResults.earnings(hand);
            assertThat(earnings.values().stream().mapToLong(Long::longValue).sum()).isZero();
            assertThat(earnings).hasSize(3);
            if (style.equals("split")) assertThat(earnings.values()).containsOnly(0L);
            if (style.equals("allin")) assertThat(hand.latestSnapshot().awards()).hasSizeGreaterThan(1);
            results.settle(UUID.randomUUID(), hand);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranked_result", Integer.class)).isEqualTo(9);
    }

    @Test void concurrentRetriesAndNewServiceInstanceNeverDoubleCount() throws Exception {
        UUID room = UUID.randomUUID();
        var hand = RankedFixtures.hand(1, "fold", 1000, 1000, 1000);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) calls.add(executor.submit(() -> results.settle(room, hand)));
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        }
        new RankedResults(jdbc, manager).settle(room, hand);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranked_hand", Integer.class)).isEqualTo(1);
        assertThat(results.player(RankedFixtures.USERS.getFirst()).orElseThrow().handCount()).isEqualTo(1);
    }

    @Test void partialFailureRollsBackLedgerAndAllAggregatesThenCanRetry() {
        UUID room = UUID.randomUUID(), missing = RankedFixtures.USERS.getLast();
        jdbc.update("DELETE FROM app_user WHERE id=?", missing);
        var hand = RankedFixtures.hand(1, "fold", 1000, 1000, 1000);
        assertThatThrownBy(() -> results.settle(room, hand)).isInstanceOf(RuntimeException.class);
        for (String table : List.of("ranked_hand", "ranked_result", "ranked_player_total"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        user(missing);
        results.settle(room, hand);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranked_result", Integer.class)).isEqualTo(3);
    }

    @Test void buyInsAndRepeatedRebuysNeverAddEarningsAndQualificationStartsAt1000() {
        UUID room = UUID.randomUUID(), user = RankedFixtures.USERS.get(1);
        for (int i = 1; i <= 999; i++) results.settle(room, RankedFixtures.hand(i, "fold", 1000, 1000, 1000));
        assertThat(results.leaderboard(0, 50).players()).isEmpty();
        var before = results.player(user).orElseThrow();
        assertThat(before.handCount()).isEqualTo(999);
        assertThat(before.netBB()).isEqualByComparingTo("-499.5");
        results.settle(room, RankedFixtures.hand(1000, "fold", 1000, 1000, 1000));
        var after = results.player(user).orElseThrow();
        assertThat(after.handCount()).isEqualTo(1000);
        assertThat(after.eligible()).isTrue();
        assertThat(after.bbPer100()).isEqualByComparingTo("-50");
        assertThat(results.leaderboard(0, 2).players()).hasSize(2);
        assertThat(results.leaderboard(1, 2).players()).hasSize(1);
    }

    @Test void exactRateThenVolumeThenIdDetermineRankEvenWhenDisplayValuesMatch() {
        jdbc.update("DELETE FROM app_user");
        var a = new RankedResults.Total(new UUID(0, 1), "a", null, 100000000000001L, 100000000000000L);
        var b = new RankedResults.Total(new UUID(0, 2), "b", null, 100000000000000L, 100000000000000L);
        assertThat(a.view(null).bbPer100()).isEqualByComparingTo(b.view(null).bbPer100());
        var twice = new RankedResults.Total(new UUID(0, 3), "c", null, 200, 2000);
        var once = new RankedResults.Total(new UUID(0, 4), "d", null, 100, 1000);
        var tied = new RankedResults.Total(new UUID(0, 5), "e", null, 100, 1000);
        // Even the smallest positive difference possible for BIGINT hand counts
        // must survive the indexed rate representation (about 1.18e-38).
        var tinyA = new RankedResults.Total(new UUID(0, 6), "f", null, 1, Long.MAX_VALUE - 1);
        var tinyB = new RankedResults.Total(new UUID(0, 7), "g", null, 1, Long.MAX_VALUE);
        var negativeA = new RankedResults.Total(new UUID(Long.MIN_VALUE, 0), "h", null, -1, Long.MAX_VALUE);
        var negativeB = new RankedResults.Total(new UUID(-1, 0), "i", null, -1, Long.MAX_VALUE - 1);
        var expected = List.of(a, b, twice, once, tied, tinyA, tinyB, negativeA, negativeB);
        for (var total : expected.reversed()) {
            user(total.userId());
            jdbc.update("INSERT INTO ranked_player_total (user_id, net_chips, hand_count) VALUES (?, ?, ?)",
                    total.userId(), total.net(), total.hands());
        }
        assertThat(results.leaderboard(0, 100).players()).extracting(RankedResults.Player::userId)
                .containsExactlyElementsOf(expected.stream().map(RankedResults.Total::userId).toList());
        assertThat(results.leaderboard(1, 3).players()).extracting(RankedResults.Player::rank).containsExactly(4L, 5L, 6L);
        for (int index = 0; index < expected.size(); index++)
            assertThat(results.player(expected.get(index).userId()).orElseThrow().rank()).isEqualTo(index + 1L);
    }

    @Test void incompleteHandsAreRejectedAndNewUsersHaveNoFabricatedRate() {
        var hand = RankedFixtures.hand(1, "fold", 1000, 1000, 1000);
        var incomplete = new com.poker.room.HandHistory(hand.handId(), hand.startedAt(), null, 5, 10,
                hand.players(), hand.events(), hand.latestSnapshot());
        assertThatThrownBy(() -> results.settle(UUID.randomUUID(), incomplete)).isInstanceOf(IllegalArgumentException.class);
        assertThat(results.player(RankedFixtures.USERS.getFirst()).orElseThrow().bbPer100()).isNull();
        assertThat(results.player(UUID.randomUUID())).isEmpty();
    }

    @Test void reopeningFileDatabasePreservesOnlyCommittedHands(@TempDir(cleanup = CleanupMode.NEVER) Path directory) throws Exception {
        try {
            String url = "jdbc:h2:file:" + directory.resolve("ranked").toAbsolutePath().toString().replace('\\', '/');
            var first = new JdbcDataSource();
            first.setURL(url);
            Flyway.configure().dataSource(first).load().migrate();
            jdbc = new JdbcTemplate(first);
            RankedFixtures.USERS.forEach(this::user);
            var beforeRestart = new RankedResults(jdbc, new DataSourceTransactionManager(first));
            UUID room = UUID.randomUUID();
            var completed = RankedFixtures.hand(1, "fold", 1000, 1000, 1000);
            beforeRestart.settle(room, completed);
            jdbc.execute("SHUTDOWN");

            var reopened = new JdbcDataSource();
            reopened.setURL(url);
            var afterRestart = new RankedResults(new JdbcTemplate(reopened), new DataSourceTransactionManager(reopened));
            assertThat(afterRestart.player(RankedFixtures.USERS.getFirst()).orElseThrow().handCount()).isEqualTo(1);
            // A retry after reopening is harmless; no active room/unfinished hand is restored.
            afterRestart.settle(room, completed);
            var freshRooms = new com.poker.room.RoomManager();
            assertThat(freshRooms.roomCount()).isZero();
            assertThat(afterRestart.player(RankedFixtures.USERS.getFirst()).orElseThrow().handCount()).isEqualTo(1);
            new JdbcTemplate(reopened).execute("SHUTDOWN");
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path directory) throws Exception {
        if (directory == null || !Files.exists(directory)) return;
        Exception last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        throw new CleanupFailure(ex);
                    }
                });
                return;
            } catch (CleanupFailure | IOException ex) {
                last = ex;
                Thread.sleep(100);
            }
        }
        if (last != null) throw last;
    }

    private static final class CleanupFailure extends RuntimeException {
        private CleanupFailure(IOException cause) { super(cause); }
    }
}
