package com.poker.ranked;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.poker.auth.AuthProvider;
import com.poker.db.user.*;
import com.poker.presence.UserSocketRegistry;
import com.poker.room.*;
import com.poker.room.application.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.env.MockEnvironment;

class RankedQueueTest {
    final RoomManager rooms = new RoomManager();
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final UserSocketRegistry sockets = new UserSocketRegistry();
    final RoomCommandService commands = new RoomCommandService(rooms, c -> PublicationReport.none());
    final RankedResults results = mock(RankedResults.class);
    final RankedQueue queue = queue(true, true);

    RankedQueue queue(boolean enabled, boolean dev) {
        var env = new MockEnvironment();
        if (dev) env.setActiveProfiles("dev");
        return new RankedQueue(rooms, commands, results, users, sockets,
                mock(SimpMessagingTemplate.class), Clock.systemUTC(), env, enabled);
    }
    MemberProfile player(AuthProvider provider) {
        UUID id = UUID.randomUUID();
        var account = mock(UserAccount.class);
        when(account.getProvider()).thenReturn(provider);
        when(users.findById(id)).thenReturn(Optional.of(account));
        sockets.register(id, id.toString());
        return new MemberProfile(id, "Player", null);
    }
    MemberProfile player() { return player(AuthProvider.DEV); }

    @Test void queuesInArrivalOrderWaitsForThreeAndRestoresAssignment() {
        var a = player(); var b = player(); var c = player();
        queue.join(a); queue.join(a); queue.join(b); queue.match();
        assertThat(queue.state(a.userId()).position()).isEqualTo(1);
        assertThat(queue.state(b.userId()).position()).isEqualTo(2);
        assertThat(rooms.roomIds()).isEmpty();
        queue.join(c); queue.match();
        assertThat(rooms.roomIds()).hasSize(1);
        var state = rooms.state(a.userId()).room();
        assertThat(state.members()).hasSize(3);
        assertThat(state.seats()).startsWith(a.userId(), b.userId(), c.userId());
        queue.join(a); queue.cancel(a.userId());
        assertThat(queue.state(a.userId()).status()).isEqualTo("ASSIGNED");
        assertThat(queue.state(a.userId()).roomId()).isEqualTo(state.roomId());
    }

    @Test void cancellationRemovesWaitingEntryAndDisconnectedUsersAreNotAssigned() {
        var a = player(); var b = player(); var c = player();
        queue.join(a); queue.join(b); queue.join(c); queue.cancel(b.userId());
        queue.match(); assertThat(rooms.roomIds()).isEmpty();
        assertThat(queue.state(b.userId()).status()).isEqualTo("IDLE");
        queue.join(b); sockets.remove(c.userId().toString()); queue.match();
        assertThat(rooms.roomIds()).isEmpty();
        sockets.register(c.userId(), c.userId().toString()); queue.match();
        assertThat(rooms.roomIds()).hasSize(1);
    }

    @Test void assignsToMostOccupiedTableAndNeverExceedsSixMembers() {
        var players = new ArrayList<MemberProfile>();
        for (int i = 0; i < 9; i++) { var p = player(); players.add(p); queue.join(p); }
        queue.match();
        assertThat(rooms.roomIds()).hasSize(2);
        UUID first = rooms.state(players.getFirst().userId()).room().roomId();
        rooms.leave(players.getFirst().userId(), first);
        var newcomer = player(); queue.join(newcomer); queue.match();
        assertThat(rooms.state(newcomer.userId()).room().roomId()).isEqualTo(first);
        assertThat(rooms.room(first).orElseThrow().memberCount()).isEqualTo(6);
    }

    @Test void simultaneousJoinsAndCancelAssignmentRaceKeepSingleMembership() throws Exception {
        var a = player(); var b = player(); var c = player();
        queue.join(b); queue.join(c);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 10; i++) calls.add(pool.submit(() -> queue.join(a)));
            for (var call : calls) call.get(5, TimeUnit.SECONDS);
            var match = pool.submit(queue::match);
            var cancel = pool.submit(() -> queue.cancel(a.userId()));
            match.get(5, TimeUnit.SECONDS); cancel.get(5, TimeUnit.SECONDS);
        }
        assertThat(queue.state(a.userId()).status()).isIn("IDLE", "ASSIGNED");
        assertThat(rooms.roomIds()).hasSizeLessThanOrEqualTo(1);
        if (!rooms.roomIds().isEmpty()) assertThat(rooms.room(rooms.roomIds().iterator().next()).orElseThrow().members()).hasSize(3);
    }

    @Test void disabledFlagAndProductionGuestRestrictionsAreEnforced() {
        var guest = player();
        assertThatThrownBy(() -> queue(false, true).join(guest)).isInstanceOf(RankedQueue.Unavailable.class);
        assertThatThrownBy(() -> queue(true, false).join(guest)).isInstanceOf(RankedQueue.Unavailable.class);
        var oauth = player(AuthProvider.GOOGLE);
        var production = queue(true, false); production.join(oauth);
        assertThat(production.state(oauth.userId()).status()).isEqualTo("QUEUED");
    }

    @Test void switchingIntoCasualPlayRemovesStaleQueueEntry() {
        var a = player(); queue.join(a);
        rooms.create(a, "Casual", 6);
        queue.match();
        assertThat(queue.state(a.userId()).status()).isEqualTo("IDLE");
        assertThat(queue.state(a.userId()).waitingPlayers()).isZero();
    }
}
