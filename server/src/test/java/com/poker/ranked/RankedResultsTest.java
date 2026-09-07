package com.poker.ranked;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        var a = new RankedResults.Total(new UUID(0, 1), "a", null, 100000000000001L, 100000000000000L);
        var b = new RankedResults.Total(new UUID(0, 2), "b", null, 100000000000000L, 100000000000000L);
        assertThat(a.view(null).bbPer100()).isEqualByComparingTo(b.view(null).bbPer100());
        assertThat(RankedResults.ORDER.compare(a, b)).isNegative();
        var twice = new RankedResults.Total(new UUID(0, 3), "c", null, 200, 2000);
        var once = new RankedResults.Total(new UUID(0, 2), "b", null, 100, 1000);
        assertThat(RankedResults.ORDER.compare(twice, once)).isNegative();
        assertThat(RankedResults.ORDER.compare(once, new RankedResults.Total(new UUID(0, 1), "a", null, 100, 1000))).isPositive();
    }

    @Test void incompleteHandsAreRejectedAndNewUsersHaveNoFabricatedRate() {
        var hand = RankedFixtures.hand(1, "fold", 1000, 1000, 1000);
        var incomplete = new com.poker.room.HandHistory(hand.handId(), hand.startedAt(), null, 5, 10,
                hand.players(), hand.events(), hand.latestSnapshot());
        assertThatThrownBy(() -> results.settle(UUID.randomUUID(), incomplete)).isInstanceOf(IllegalArgumentException.class);
        assertThat(results.player(RankedFixtures.USERS.getFirst()).orElseThrow().bbPer100()).isNull();
        assertThat(results.player(UUID.randomUUID())).isEmpty();
    }

    @Test void reopeningFileDatabasePreservesOnlyCommittedHands(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
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
    }
}
