package com.poker.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The one public table the lobby always offers: memberless, listed, never collected. */
class HouseTableTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration GRACE = Duration.ofMinutes(10);
    private static final Duration IDLE = Duration.ofMinutes(60);

    @Test
    void isListedInTheLobbyWithNobodyInIt() {
        RoomManager manager = manager();

        RoomChange seeded = manager.createHouseTable("House Table", RoomConfig.PUBLIC_SEATS,
                RoomConfig.PUBLIC_DEFAULT);

        List<LobbyRoomState> rooms = manager.lobby().rooms();
        assertEquals(1, rooms.size());
        assertEquals("House Table", rooms.get(0).name());
        assertEquals(0, rooms.get(0).memberCount());
        assertEquals(0, rooms.get(0).seatedCount());
        assertEquals(RoomConfig.PUBLIC_SEATS, rooms.get(0).maxSeats());
        assertTrue(manager.room(seeded.roomId()).isPresent());
    }

    @Test
    void survivesItsLastMemberLeaving() {
        RoomManager manager = manager();
        RoomChange seeded = manager.createHouseTable("House Table", RoomConfig.PUBLIC_SEATS,
                RoomConfig.PUBLIC_DEFAULT);
        UUID roomId = seeded.roomId();
        MemberProfile alice = profile("Alice");

        manager.join(alice, seeded.room().joinCodeValue());
        assertEquals(1, manager.lobby().rooms().get(0).memberCount());

        manager.leave(alice.userId(), roomId);

        assertEquals(1, manager.roomCount());
        assertTrue(manager.room(roomId).isPresent());
        assertEquals(1, manager.lobby().rooms().size());
        assertEquals(0, manager.lobby().rooms().get(0).memberCount());
    }

    @Test
    void isNeverClosedByIdleCleanupWhileOrdinaryTablesAre() {
        RoomManager manager = manager();
        RoomChange house = manager.createHouseTable("House Table", RoomConfig.PUBLIC_SEATS,
                RoomConfig.PUBLIC_DEFAULT);
        RoomChange ordinary = manager.create(profile("Bob"), "Bob's Table", 6,
                RoomConfig.PUBLIC_DEFAULT, false);
        Instant wellPastEveryDeadline = NOW.plus(IDLE).plus(IDLE);

        assertTrue(cleanUp(manager, house.roomId(), wellPastEveryDeadline).change() == null);
        assertTrue(cleanUp(manager, ordinary.roomId(), wellPastEveryDeadline).change() != null);

        assertTrue(manager.room(house.roomId()).isPresent());
        assertTrue(manager.room(ordinary.roomId()).isEmpty());
    }

    private static RoomManager.CleanupOutcome cleanUp(RoomManager manager, UUID roomId, Instant now) {
        RoomManager.CleanupIntent intent = manager
                .cleanupIntent(roomId, GRACE, GRACE, IDLE).orElseThrow();
        return manager.cleanupDue(
                roomId, intent.generation(), intent.due(), now, GRACE, GRACE, IDLE);
    }

    private static RoomManager manager() {
        // The code alphabet excludes 0 and 1, so number the fixtures from 2 up.
        AtomicInteger sequence = new AtomicInteger(1);
        return new RoomManager(CLOCK, new JoinCodeGenerator(
                () -> JoinCode.of("ABCDE" + sequence.incrementAndGet())));
    }

    private static MemberProfile profile(String displayName) {
        return new MemberProfile(UUID.randomUUID(), displayName, null);
    }
}
