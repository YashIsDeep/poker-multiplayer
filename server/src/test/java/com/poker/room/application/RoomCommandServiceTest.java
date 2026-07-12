package com.poker.room.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poker.room.JoinCode;
import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomCommandServiceTest {
    @Test
    void publicationFailureDoesNotUndoCommittedMutation() {
        RoomManager manager = new RoomManager(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new JoinCodeGenerator(() -> new JoinCode("ABC234")));
        RoomEventSink failing = change -> {
            throw new IllegalStateException("synthetic broker failure");
        };
        RoomCommandService service = new RoomCommandService(manager, failing);
        RoomCommandResult result = service.create(profile("alice"), "room", 2);

        assertTrue(result.publication().hasFailures());
        assertEquals(1, manager.roomCount());
        assertTrue(manager.currentRoom(profile("alice").userId()).isPresent());
        assertEquals(1, manager.state(profile("alice").userId()).lobby().rooms().size());
    }

    private static MemberProfile profile(String name) {
        return new MemberProfile(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
    }
}
