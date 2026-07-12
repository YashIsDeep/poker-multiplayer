package com.poker.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.poker.room.MemberProfile;
import com.poker.room.RoomManager;
import com.poker.room.RoomState;
import com.poker.room.JoinCodeGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomSnapshotMapperTest {
    @Test
    void mapsViewerSpecificRoomAndWaitingGameState() {
        RoomManager manager = new RoomManager(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new JoinCodeGenerator(() -> new com.poker.room.JoinCode("ABC234")));
        MemberProfile alice = profile("alice");
        MemberProfile bob = profile("bob");
        var created = manager.create(alice, "room", 2);
        manager.join(bob, created.room().joinCodeValue());
        manager.takeSeat(alice.userId(), created.roomId(), 0);
        RoomState room = manager.room(created.roomId()).orElseThrow();

        RoomSnapshotMapper mapper = new RoomSnapshotMapper();
        var aliceView = mapper.forViewer(room, alice.userId(), manager.membershipEpoch(alice.userId()), Instant.EPOCH);
        var bobView = mapper.forViewer(room, bob.userId(), manager.membershipEpoch(bob.userId()), Instant.EPOCH);
        assertEquals(alice.userId(), aliceView.viewer().userId());
        assertEquals(bob.userId(), bobView.viewer().userId());
        assertEquals(0, aliceView.viewer().seat());
        assertNull(bobView.viewer().seat());
        assertNotNull(aliceView.game());
        assertEquals("WAITING", aliceView.game().tableStatus());
        assertEquals(0, aliceView.game().handId());
        assertNull(aliceView.game().phase());
        assertEquals(room.maxSeats(), aliceView.seats().size());
        assertEquals(alice.userId(), aliceView.seats().get(0).userId());
        assertEquals(room.config().minBuyIn(), aliceView.config().minBuyIn());
        assertEquals(room.config().maxBuyIn(), aliceView.config().maxBuyIn());
    }

    private static MemberProfile profile(String name) {
        return new MemberProfile(UUID.nameUUIDFromBytes(name.getBytes()), name, null);
    }
}
