package com.poker.ranked;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class RankedReadPerformanceTest {
    JdbcTemplate jdbc;
    RankedResults results;
    CountingDataSource counted;
    AtomicLong clock = new AtomicLong();

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:ranked_load_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(source).load().migrate();
        counted = new CountingDataSource(source);
        jdbc = new JdbcTemplate(counted);
        results = new RankedResults(jdbc, new DataSourceTransactionManager(counted), clock::get);
    }

    @Test void twentyThousandPlayersUseIndexedBoundedPagesAndConstantQueryCounts() {
        jdbc.update("""
                INSERT INTO app_user (id, provider, provider_subject, display_name, created_at, last_login_at)
                SELECT RANDOM_UUID(), 'DEV', CAST(x AS VARCHAR), 'Player ' || x, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM SYSTEM_RANGE(1, 20000)
                """);
        jdbc.update("INSERT INTO ranked_player_total (user_id, net_chips, hand_count) SELECT id, CAST(provider_subject AS BIGINT), 1000 FROM app_user");
        String plan = jdbc.queryForObject("EXPLAIN ANALYZE " + RankedResults.PAGE_SQL, String.class, 0, 50);
        assertThat(plan).contains("RANKED_PLAYER_BOARD", "index sorted", "scanCount: 50");
        String deepPlan = jdbc.queryForObject("EXPLAIN ANALYZE " + RankedResults.PAGE_SQL, String.class, 10_000, 50);
        assertThat(deepPlan).contains("index sorted", "scanCount: 10050");

        counted.reset();
        var board = assertTimeout(Duration.ofSeconds(3), () -> results.leaderboard(0, 50));
        assertThat(board.totalPlayers()).isEqualTo(20_000);
        assertThat(board.players()).hasSize(50);
        assertThat(board.players().getFirst().netBB()).isEqualByComparingTo("2000");
        assertThat(counted.queries.get()).isEqualTo(2);
        assertThat(counted.rows.get()).isEqualTo(51); // scalar count plus one page, never all players
        counted.reset();
        var last = board.players().getLast();
        assertThat(results.player(last.userId()).orElseThrow().rank()).isEqualTo(50);
        assertThat(counted.queries.get()).isEqualTo(1);
        assertThat(counted.rows.get()).isEqualTo(1);
        counted.reset();
        for (int i = 0; i < 1000; i++) {
            results.leaderboard(0, 50);
            results.player(last.userId());
        }
        assertThat(counted.queries.get()).isZero();
        counted.reset();
        assertThat(results.leaderboard(Integer.MAX_VALUE, 100).players()).isEmpty();
        assertThat(counted.queries.get()).isEqualTo(1);
    }

    @Test void cacheBoundsNegativeLookupsExpiresAndCoalescesConcurrentMisses() throws Exception {
        UUID missing = UUID.randomUUID();
        assertThat(results.player(missing)).isEmpty();
        counted.reset();
        assertThat(results.player(missing)).isEmpty();
        assertThat(counted.queries.get()).isZero();
        clock.addAndGet(RankedResults.CACHE_TTL_NANOS);
        results.player(missing);
        assertThat(counted.queries.get()).isEqualTo(1);
        for (int i = 0; i < RankedResults.CACHE_ENTRIES_PER_KIND; i++) results.player(UUID.randomUUID());
        counted.reset();
        results.player(missing);
        assertThat(counted.queries.get()).isEqualTo(1); // oldest negative entry was evicted

        UUID concurrentMissing = UUID.randomUUID();
        counted.reset();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(1);
            var pending = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) pending.add(executor.submit(() -> {
                ready.await();
                return results.player(concurrentMissing);
            }));
            ready.countDown();
            for (var future : pending) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(counted.queries.get()).isEqualTo(1);

        counted.reset();
        for (int i = 0; i <= RankedResults.CACHE_ENTRIES_PER_KIND; i++) results.leaderboard(i, 1);
        counted.reset();
        results.leaderboard(0, 1);
        assertThat(counted.queries.get()).isEqualTo(1); // board keys are bounded independently
    }

    @Test void settlementInvalidatesCachedZeroAndMissingRank() {
        for (UUID id : RankedFixtures.USERS) jdbc.update("""
                INSERT INTO app_user (id, provider, provider_subject, display_name, created_at, last_login_at)
                VALUES (?, 'DEV', ?, 'Player', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, id.toString());
        UUID user = RankedFixtures.USERS.getFirst();
        assertThat(results.player(user).orElseThrow().handCount()).isZero();
        results.settle(UUID.randomUUID(), RankedFixtures.hand(1, "fold", 1000, 1000, 1000));
        assertThat(results.player(user).orElseThrow().handCount()).isEqualTo(1);
    }

    private static final class CountingDataSource extends DelegatingDataSource {
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger rows = new AtomicInteger();
        CountingDataSource(DataSource source) { super(source); }
        void reset() { queries.set(0); rows.set(0); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return connection(super.getConnection(user, password));
        }
        private Connection connection(Connection connection) {
            return proxy(Connection.class, (ignored, method, args) -> {
                Object value = invoke(connection, method, args);
                if (value instanceof PreparedStatement statement) return statement(PreparedStatement.class, statement);
                if (value instanceof Statement statement) return statement(Statement.class, statement);
                return value;
            });
        }
        private <T extends Statement> T statement(Class<T> type, T statement) {
            return proxy(type, (ignored, method, args) -> {
                Object value = invoke(statement, method, args);
                if (method.getName().equals("executeQuery") && value instanceof ResultSet result) {
                    queries.incrementAndGet();
                    return proxy(ResultSet.class, (unused, cursorMethod, cursorArgs) -> {
                        Object answer = invoke(result, cursorMethod, cursorArgs);
                        if (cursorMethod.getName().equals("next") && Boolean.TRUE.equals(answer)) rows.incrementAndGet();
                        return answer;
                    });
                }
                return value;
            });
        }
        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try { return method.invoke(target, arguments); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
        }
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
