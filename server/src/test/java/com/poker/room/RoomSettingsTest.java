package com.poker.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomSettingsTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void privateAdminUpdatesOnlyDefaultsForFutureSeats() {
        RoomManager rooms = manager(17);
        MemberProfile owner = profile("Owner");
        MemberProfile newcomer = profile("Newcomer");
        RoomConfig original = RoomConfig.table(100, 10_000, true, 12_000);
        RoomChange created = rooms.create(owner, "Private", 3, original, true);
        rooms.takeSeat(owner.userId(), created.roomId(), 0);

        RoomConfig updated = RoomConfig.table(200, 20_000, true, 30_000);
        RoomChange changed = rooms.updateSettings(owner.userId(), created.roomId(), updated);
        rooms.join(newcomer, created.room().joinCodeValue());
        RoomChange seated = rooms.takeSeat(newcomer.userId(), created.roomId(), 1);

        assertThat(changed.room().config()).isEqualTo(updated);
        assertThat(seated.room().game().seats())
                .extracting(TableSeatState::stack)
                .containsExactly(10_000L, 20_000L);
    }

    @Test
    void onlyPrivateRoomCreatorCanEditSettings() {
        RoomManager rooms = manager(23);
        MemberProfile owner = profile("Owner");
        MemberProfile guest = profile("Guest");
        RoomChange privateRoom = rooms.create(
                owner, "Private", 2, RoomConfig.PUBLIC_DEFAULT, true);
        rooms.join(guest, privateRoom.room().joinCodeValue());

        assertThatThrownBy(() -> rooms.updateSettings(
                guest.userId(), privateRoom.roomId(), RoomConfig.table(50_000, 5_000_000, true, 5_000_000)))
                .isInstanceOf(RoomException.class)
                .extracting(error -> ((RoomException) error).error())
                .isEqualTo(RoomError.NOT_ROOM_ADMIN);

        RoomManager publicRooms = manager(29);
        MemberProfile publicOwner = profile("Public owner");
        RoomChange publicRoom = publicRooms.create(
                publicOwner, "Public", 2, RoomConfig.PUBLIC_DEFAULT, false);
        assertThatThrownBy(() -> publicRooms.updateSettings(
                publicOwner.userId(), publicRoom.roomId(), RoomConfig.PUBLIC_DEFAULT))
                .isInstanceOf(RoomException.class)
                .extracting(error -> ((RoomException) error).error())
                .isEqualTo(RoomError.SETTINGS_NOT_EDITABLE);
    }

    @Test
    void publicDefaultsMatchProductChipScale() {
        assertThat(RoomConfig.PUBLIC_DEFAULT.bigBlind()).isEqualTo(100_000);
        assertThat(RoomConfig.PUBLIC_DEFAULT.startingStack()).isEqualTo(10_000_000);
        assertThat(RoomConfig.PUBLIC_DEFAULT.rebuyStack()).isEqualTo(10_000_000);
        assertThat(RoomConfig.PUBLIC_DEFAULT.rebuysEnabled()).isFalse();
        RoomConfig trillionChipTable = RoomConfig.table(
                1_000_000_000, 1_000_000_000_000L, true, 500_000_000_000L);
        assertThat(trillionChipTable.startingStack()).isEqualTo(1_000_000_000_000L);
        assertThat(trillionChipTable.rebuyStack()).isEqualTo(500_000_000_000L);
    }

    private static RoomManager manager(long seed) {
        return new RoomManager(CLOCK, new JoinCodeGenerator(new Random(seed)));
    }

    private static MemberProfile profile(String name) {
        return new MemberProfile(UUID.randomUUID(), name, null);
    }
}
