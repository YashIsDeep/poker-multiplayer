package com.poker.ranked;

import com.poker.engine.game.HandPhase;
import com.poker.room.HandHistory;
import com.poker.room.application.RankedSettlement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RankedResults implements RankedSettlement {
    public static final int QUALIFYING_HANDS = 1000;
    private static final Logger log = LoggerFactory.getLogger(RankedResults.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    static final int CACHE_ENTRIES_PER_KIND = 128;
    static final long CACHE_TTL_NANOS = java.time.Duration.ofSeconds(5).toNanos();
    private final LongSupplier nanoTime;
    private final Object cacheLock = new Object();
    private final Map<Page, Cached<Board>> boards = new LinkedHashMap<>(16, .75f, true);
    private final Map<UUID, Cached<Optional<Player>>> players = new LinkedHashMap<>(16, .75f, true);

    private record Page(int page, int size) {}
    private record Cached<V>(V value, long createdAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public RankedResults(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, System::nanoTime);
    }

    RankedResults(JdbcTemplate jdbc, PlatformTransactionManager manager, LongSupplier nanoTime) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.nanoTime = nanoTime;
    }

    // One application owns all live tables. Serialize settlements, including commit,
    // so concurrent tables cannot lose aggregate updates or race first insertion.
    @Override
    public synchronized void settle(UUID roomId, HandHistory hand) {
        Map<UUID, Long> results = earnings(hand);
        try {
            transactions.executeWithoutResult(transaction -> {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM ranked_hand WHERE room_id=? AND hand_id=?",
                        Long.class, roomId, hand.handId()) != 0) return;
                jdbc.update("INSERT INTO ranked_hand VALUES (?, ?, ?, ?, ?)", roomId, hand.handId(),
                        hand.completedAt().atOffset(java.time.ZoneOffset.UTC), hand.bigBlind(), results.size());
                results.forEach((user, net) -> {
                    jdbc.update("INSERT INTO ranked_result VALUES (?, ?, ?, ?)", roomId, hand.handId(), user, net);
                    int updated = jdbc.update("UPDATE ranked_player_total SET net_chips=net_chips+?, hand_count=hand_count+1 WHERE user_id=?", net, user);
                    if (updated == 0) jdbc.update("INSERT INTO ranked_player_total (user_id, net_chips, hand_count) VALUES (?, ?, 1)", user, net);
                });
            });
            synchronized (cacheLock) {
                boards.clear();
                players.clear();
            }
        } catch (RuntimeException ex) {
            log.error("Ranked settlement pending roomId={} handId={}", roomId, hand.handId(), ex);
            throw ex;
        }
    }

    static Map<UUID, Long> earnings(HandHistory hand) {
        if (hand.completedAt() == null || hand.latestSnapshot().phase() != HandPhase.COMPLETE
                || hand.bigBlind() != 10 || hand.players().size() < 3 || hand.players().size() > 6) {
            throw new IllegalArgumentException("not a completed ranked hand");
        }
        Map<UUID, Long> results = new LinkedHashMap<>();
        long sum = 0;
        for (var player : hand.players()) {
            var ending = hand.latestSnapshot().players().stream()
                    .filter(p -> p.id().value().equals(player.userId().toString()) && p.seat() == player.seat())
                    .findFirst().orElseThrow();
            long net = Math.subtractExact(ending.stack(), player.startingStack());
            if (results.put(player.userId(), net) != null) throw new IllegalArgumentException("duplicate participant");
            sum = Math.addExact(sum, net);
        }
        if (sum != 0) throw new IllegalArgumentException("ranked hand does not conserve chips");
        return results;
    }

    public record Player(UUID userId, String displayName, String avatarUrl, BigDecimal bbPer100,
                         BigDecimal netBB, long handCount, boolean eligible, Long rank) {}
    public record Board(List<Player> players, int page, int pageSize, long totalPlayers, int qualifyingHands) {}
    record Total(UUID userId, String displayName, String avatarUrl, long net, long hands) {
        Player view(Long rank) {
            return new Player(userId, displayName, avatarUrl,
                    hands == 0 ? null : BigDecimal.valueOf(net).multiply(BigDecimal.TEN)
                            .divide(BigDecimal.valueOf(hands), 12, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(net, 1), hands, hands >= QUALIFYING_HANDS, rank);
        }
    }
    // Include the constant qualified column in ORDER BY: H2 then traverses the
    // composite index in order and stops after offset + limit, without a sort.
    static final String PAGE_SQL = """
            SELECT user_id, net_chips, hand_count, rank_rate FROM ranked_player_total
            WHERE qualified=TRUE
            ORDER BY qualified, rank_rate DESC, hand_count DESC, user_id
            OFFSET ? ROWS FETCH FIRST ? ROWS ONLY
            """;
    private static final String BOARD_SQL = """
            SELECT u.id, u.display_name, u.avatar_url, p.net_chips, p.hand_count,
                   ? + ROW_NUMBER() OVER (ORDER BY p.rank_rate DESC, p.hand_count DESC, p.user_id) AS player_rank
            FROM (
            """ + PAGE_SQL + """
            ) p JOIN app_user u ON u.id=p.user_id
            ORDER BY player_rank
            """;

    public Board leaderboard(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("invalid page");
        return cached(boards, new Page(page, size), () -> {
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM ranked_player_total WHERE qualified=TRUE", Long.class);
            long offset = (long) page * size;
            List<Player> rows = offset >= total ? List.of() : jdbc.query(BOARD_SQL,
                    (rs, row) -> new Total(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getLong(5)).view(rs.getLong(6)), offset, offset, size);
            return new Board(List.copyOf(rows), page, size, total, QUALIFYING_HANDS);
        });
    }

    public Optional<Player> player(UUID id) {
        return cached(players, id, () -> jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_url, COALESCE(t.net_chips, 0), COALESCE(t.hand_count, 0),
                    CASE WHEN t.qualified THEN 1
                        + (SELECT COUNT(*) FROM ranked_player_total r WHERE r.qualified=TRUE AND r.rank_rate > t.rank_rate)
                        + (SELECT COUNT(*) FROM ranked_player_total r WHERE r.qualified=TRUE AND r.rank_rate = t.rank_rate AND r.hand_count > t.hand_count)
                        + (SELECT COUNT(*) FROM ranked_player_total r WHERE r.qualified=TRUE AND r.rank_rate = t.rank_rate AND r.hand_count = t.hand_count AND r.user_id < t.user_id)
                    ELSE NULL END AS player_rank
                FROM app_user u LEFT JOIN ranked_player_total t ON t.user_id=u.id WHERE u.id=?
                """, (rs, row) -> new Total(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)).view(rs.getObject(6, Long.class)), id).stream().findFirst());
    }

    private <K, V> V cached(Map<K, Cached<V>> cache, K key, Supplier<V> load) {
        synchronized (cacheLock) {
            long now = nanoTime.getAsLong();
            var found = cache.get(key);
            if (found != null && now - found.createdAt < CACHE_TTL_NANOS) return found.value;
            // Serialize cache misses so simultaneous requests for the same key
            // share one query. Both successful and missing players are bounded.
            V value = load.get();
            if (cache.size() >= CACHE_ENTRIES_PER_KIND && !cache.containsKey(key))
                cache.remove(cache.keySet().iterator().next());
            cache.put(key, new Cached<>(value, nanoTime.getAsLong()));
            return value;
        }
    }
}
