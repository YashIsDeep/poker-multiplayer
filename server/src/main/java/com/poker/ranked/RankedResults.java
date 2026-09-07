package com.poker.ranked;

import com.poker.engine.game.HandPhase;
import com.poker.room.HandHistory;
import com.poker.room.application.RankedSettlement;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
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

    public RankedResults(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
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
                    if (updated == 0) jdbc.update("INSERT INTO ranked_player_total VALUES (?, ?, 1)", user, net);
                });
            });
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
    static final Comparator<Total> ORDER = (a, b) -> {
        // Compare exact rational win rates, never rounded display values.
        int rate = BigInteger.valueOf(b.net).multiply(BigInteger.valueOf(a.hands))
                .compareTo(BigInteger.valueOf(a.net).multiply(BigInteger.valueOf(b.hands)));
        if (rate != 0) return rate;
        int hands = Long.compare(b.hands, a.hands);
        return hands != 0 ? hands : a.userId.toString().compareTo(b.userId.toString());
    };

    private List<Total> totals() {
        return jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_url, t.net_chips, t.hand_count
                FROM ranked_player_total t JOIN app_user u ON u.id=t.user_id
                """, (rs, row) -> new Total(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5)));
    }

    public Board leaderboard(int page, int size) {
        var eligible = totals().stream().filter(t -> t.hands >= QUALIFYING_HANDS).sorted(ORDER).toList();
        int start = (int) Math.min((long) page * size, eligible.size());
        List<Player> players = new ArrayList<>();
        for (int i = start; i < Math.min(start + size, eligible.size()); i++) players.add(eligible.get(i).view((long) i + 1));
        return new Board(players, page, size, eligible.size(), QUALIFYING_HANDS);
    }

    public Optional<Player> player(UUID id) {
        var all = totals();
        var total = all.stream().filter(t -> t.userId.equals(id)).findFirst();
        if (total.isPresent()) {
            var t = total.get();
            Long rank = t.hands >= QUALIFYING_HANDS
                    ? 1 + all.stream().filter(other -> other.hands >= QUALIFYING_HANDS && ORDER.compare(other, t) < 0).count() : null;
            return Optional.of(t.view(rank));
        }
        return jdbc.query("SELECT id, display_name, avatar_url FROM app_user WHERE id=?",
                (rs, row) -> new Total(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), 0, 0).view(null), id)
                .stream().findFirst();
    }
}
