package com.poker.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoomManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void createJoinSeatLeaveAndGcAreAtomic() {
        RoomManager manager = new RoomManager(CLOCK, codes("ABC234", "DEF234"));
        MemberProfile alice = profile("Alice");
        MemberProfile bob = profile("Bob");

        RoomChange created = manager.create(alice, "  Late   Night ", 2);
        assertEquals(1, created.roomRevision());
        assertEquals(1, created.lobbyRevision());
        UUID roomId = created.roomId();
        String code = created.room().joinCodeValue();
        assertNull(created.room().member(alice.userId()).seat());

        RoomChange joined = manager.join(bob, code.toLowerCase(Locale.ROOT));
        assertEquals(2, joined.room().memberCount());
        assertEquals(2, joined.roomRevision());
        assertEquals(2, joined.lobbyRevision());

        RoomChange seated = manager.takeSeat(alice.userId(), roomId, 0);
        assertEquals(3, seated.roomRevision());
        assertEquals(3, seated.lobbyRevision());
        assertEquals(0, seated.room().member(alice.userId()).seat());
        assertThrows(RoomException.class, () -> manager.takeSeat(bob.userId(), roomId, 0));

        long bobBeforeLeave = manager.membershipEpoch(bob.userId());
        RoomChange bobLeft = manager.leave(bob.userId(), roomId);
        assertEquals(bobBeforeLeave + 1, bobLeft.exitedMembershipEpoch());
        assertEquals(1, bobLeft.room().memberCount());
        assertEquals(4, bobLeft.roomRevision());

        RoomChange aliceLeft = manager.leave(alice.userId(), roomId);
        assertNull(aliceLeft.room());
        assertEquals(0, manager.roomCount());
        assertEquals(0, manager.state(alice.userId()).room() == null ? 0 : 1);
        assertEquals(0, manager.lobby().rooms().size());
    }

    @Test
    void idempotentOperationsDoNotAdvanceRevisions() {
        RoomManager manager = new RoomManager(CLOCK, codes("ABC234", "DEF234"));
        MemberProfile alice = profile("Alice");
        RoomChange created = manager.create(alice, "Room", 2);
        UUID roomId = created.roomId();
        RoomChange joinedAgain = manager.join(alice, created.room().joinCodeValue());
        assertEquals(created.roomRevision(), joinedAgain.roomRevision());
        assertEquals(created.lobbyRevision(), joinedAgain.lobbyRevision());
        RoomChange seated = manager.takeSeat(alice.userId(), roomId, 1);
        RoomChange sameSeat = manager.takeSeat(alice.userId(), roomId, 1);
        assertEquals(seated.roomRevision(), sameSeat.roomRevision());
        RoomChange standing = manager.stand(alice.userId(), roomId);
        RoomChange alreadyStanding = manager.stand(alice.userId(), roomId);
        assertEquals(standing.roomRevision(), alreadyStanding.roomRevision());
    }

    @Test
    void repeatedLeaveSucceedsWhileFormerRoomStillExists() {
        RoomManager manager = new RoomManager(CLOCK, codes("ABC234"));
        MemberProfile alice = profile("Alice");
        MemberProfile bob = profile("Bob");
        RoomChange created = manager.create(alice, "Room", 2);
        manager.join(bob, created.room().joinCodeValue());

        RoomChange firstLeave = manager.leave(bob.userId(), created.roomId());
        RoomChange repeatedLeave = manager.leave(bob.userId(), created.roomId());

        assertEquals(firstLeave.lobbyRevision(), repeatedLeave.lobbyRevision());
        assertEquals(firstLeave.exitedMembershipEpoch(), repeatedLeave.exitedMembershipEpoch());
        assertNull(repeatedLeave.room());
        assertNull(repeatedLeave.exitedUserId());
        assertEquals(1, manager.room(created.roomId()).orElseThrow().memberCount());
    }

    @Test
    void codeExhaustionLeavesStateUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        JoinCodeGenerator generator = new JoinCodeGenerator(() -> {
            calls.incrementAndGet();
            return new JoinCode("ABC234");
        });
        RoomManager manager = new RoomManager(CLOCK, generator);
        manager.create(profile("Alice"), "Room", 2);
        RoomException exhausted = assertThrows(RoomException.class,
                () -> manager.create(profile("Bob"), "Other", 2));
        assertEquals(RoomError.JOIN_CODE_EXHAUSTED, exhausted.error());
        assertEquals(32 + 1, calls.get());
        assertEquals(1, manager.roomCount());
    }

    private static JoinCodeGenerator codes(String... values) {
        Iterator<String> iterator = List.of(values).iterator();
        return new JoinCodeGenerator(() -> iterator.hasNext() ? new JoinCode(iterator.next()) : new JoinCode("ZZZ234"));
    }

    private static MemberProfile profile(String name) {
        return new MemberProfile(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
    }
}
