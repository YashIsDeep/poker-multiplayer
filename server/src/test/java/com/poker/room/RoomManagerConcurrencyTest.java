package com.poker.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoomManagerConcurrencyTest {
    @Test
    void oneUserWinsTheFinalSlotRace() throws Exception {
        RoomManager manager = new RoomManager(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new JoinCodeGenerator(() -> new JoinCode("ABC234")));
        MemberProfile owner = profile("owner");
        RoomChange created = manager.create(owner, "room", 2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> join(start, manager, "one", created.room().joinCodeValue()));
            Future<Boolean> second = executor.submit(() -> join(start, manager, "two", created.room().joinCodeValue()));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(2, manager.room(created.roomId()).orElseThrow().memberCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneUserWinsTheSeatRaceAndIndexesRemainBidirectional() throws Exception {
        RoomManager manager = new RoomManager(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new JoinCodeGenerator(() -> new JoinCode("ABC234")));
        RoomChange created = manager.create(profile("owner"), "room", 3);
        manager.join(profile("one"), created.room().joinCodeValue());
        manager.join(profile("two"), created.room().joinCodeValue());
        UUID roomId = created.roomId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> seat(start, manager, "one", roomId));
            Future<Boolean> second = executor.submit(() -> seat(start, manager, "two", roomId));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(1, manager.room(roomId).orElseThrow().seatedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean join(CountDownLatch start, RoomManager manager, String name, String code) throws Exception {
        start.await();
        try {
            manager.join(profile(name), code);
            return true;
        } catch (RoomException ex) {
            return false;
        }
    }

    private static boolean seat(CountDownLatch start, RoomManager manager, String name, UUID roomId) throws Exception {
        start.await();
        try {
            manager.takeSeat(profile(name).userId(), roomId, 0);
            return true;
        } catch (RoomException ex) {
            return false;
        }
    }

    private static MemberProfile profile(String name) {
        return new MemberProfile(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
    }
}
