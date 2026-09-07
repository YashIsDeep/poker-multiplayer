package com.poker.ranked;

import com.poker.auth.AuthProvider;
import com.poker.db.user.UserAccountRepository;
import com.poker.presence.UserSocketRegistry;
import com.poker.room.*;
import com.poker.room.application.RoomCommandService;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Single-server FIFO. All queue changes and assignments share this monitor. */
@Service
public class RankedQueue {
    private static final Logger log = LoggerFactory.getLogger(RankedQueue.class);
    private final LinkedHashMap<UUID, MemberProfile> waiting = new LinkedHashMap<>();
    private final Map<UUID, Instant> disconnected = new HashMap<>();
    private final RoomManager rooms;
    private final RoomCommandService commands;
    private final RankedResults results;
    private final UserAccountRepository users;
    private final UserSocketRegistry sockets;
    private final SimpMessagingTemplate messaging;
    private final Clock clock;
    private final boolean enabled;
    private final boolean development;
    private long revision;

    public RankedQueue(RoomManager rooms, RoomCommandService commands, RankedResults results,
                       UserAccountRepository users, UserSocketRegistry sockets,
                       SimpMessagingTemplate messaging, Clock clock, Environment environment,
                       @Value("${poker.ranked.enabled:false}") boolean enabled) {
        this.rooms = rooms; this.commands = commands; this.results = results;
        this.users = users; this.sockets = sockets; this.messaging = messaging; this.clock = clock;
        this.enabled = enabled;
        development = environment.acceptsProfiles(Profiles.of("dev", "test"));
    }

    public record State(boolean enabled, long revision, String status, int position, int waitingPlayers, UUID roomId) {}

    public synchronized State state(UUID user) {
        RoomState room = rooms.state(user).room();
        int position = 0;
        for (UUID id : waiting.keySet()) { position++; if (id.equals(user)) break; }
        return new State(enabled, revision,
                room != null && room.game().ranked() ? "ASSIGNED" : waiting.containsKey(user) ? "QUEUED" : "IDLE",
                waiting.containsKey(user) ? position : 0, waiting.size(),
                room != null && room.game().ranked() ? room.roomId() : null);
    }

    public synchronized void join(MemberProfile profile) {
        if (!enabled) throw new Unavailable("Ranked play is disabled");
        AuthProvider provider = users.findById(profile.userId()).orElseThrow(() -> new Unavailable("Account required")).getProvider();
        if (provider != AuthProvider.GOOGLE && provider != AuthProvider.DISCORD
                && !(development && provider == AuthProvider.DEV)) throw new Unavailable("OAuth account required");
        RoomState room = rooms.state(profile.userId()).room();
        if (room != null) {
            if (!room.game().ranked()) throw new RoomException(RoomError.ALREADY_IN_ANOTHER_ROOM);
            publish(profile.userId());
            return;
        }
        if (!waiting.containsKey(profile.userId())) {
            if (waiting.size() >= 1000) throw new Unavailable("Queue is full");
            waiting.put(profile.userId(), profile);
            revision++;
        }
        publishWaiting();
    }

    public synchronized void cancel(UUID user) {
        if (waiting.remove(user) != null) { disconnected.remove(user); revision++; }
        // Cancellation never undoes an assignment that already won the race.
        publish(user);
        publishWaiting();
    }

    @Scheduled(fixedDelayString = "${poker.ranked.queue-interval-ms:1000}")
    public synchronized void match() {
        if (!enabled) return;
        try {
            var changes = commands.consolidateRanked();
            if (!changes.isEmpty()) {
                revision++;
                changes.stream().flatMap(c -> c.roomRecipients().stream()).distinct().forEach(this::publish);
            }
        } catch (RuntimeException ex) {
            log.error("Ranked table consolidation failed", ex);
        }
        if (waiting.isEmpty()) return;
        boolean changed = false;
        for (UUID user : List.copyOf(waiting.keySet())) {
            boolean hasRoom = rooms.state(user).room() != null;
            boolean offline = sockets.count(user) == 0;
            if (offline) disconnected.putIfAbsent(user, clock.instant());
            else disconnected.remove(user);
            if (hasRoom || (offline && !clock.instant().isBefore(disconnected.get(user).plusSeconds(30)))) {
                waiting.remove(user); disconnected.remove(user); revision++; changed = true; publish(user);
            }
        }
        while (true) {
            List<MemberProfile> ready = waiting.values().stream().filter(p -> sockets.count(p.userId()) > 0).toList();
            if (ready.isEmpty() || (!rooms.hasRankedVacancy() && ready.size() < 3)) break;
            MemberProfile player = ready.getFirst();
            try {
                commands.assignRanked(player, results);
                waiting.remove(player.userId()); disconnected.remove(player.userId()); revision++; changed = true;
                publish(player.userId());
            } catch (RuntimeException ex) {
                log.error("Ranked assignment failed userId={}", player.userId(), ex);
                break;
            }
        }
        if (changed) publishWaiting();
    }

    private void publishWaiting() { waiting.keySet().forEach(this::publish); }
    private void publish(UUID user) {
        try { messaging.convertAndSendToUser(user.toString(), "/queue/ranked", state(user)); }
        catch (RuntimeException ex) { log.warn("Ranked queue publication failed userId={}", user, ex); }
    }

    public static class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
