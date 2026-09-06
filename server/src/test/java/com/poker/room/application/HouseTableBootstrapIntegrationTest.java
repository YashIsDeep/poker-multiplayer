package com.poker.room.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.auth.AuthProvider;
import com.poker.auth.ModeratorPolicy;
import com.poker.auth.SessionUserPrincipal;
import com.poker.room.LobbyRoomState;
import com.poker.room.RoomConfig;
import com.poker.room.RoomManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** The booted application seeds exactly one public table and binds the allowlist. */
@SpringBootTest
@TestPropertySource(properties = "poker.house-table.enabled=true")
class HouseTableBootstrapIntegrationTest {

    @Autowired
    private RoomManager rooms;

    @Autowired
    private ModeratorPolicy moderators;

    @Test
    void startupLeavesOnePublicTableInTheLobby() {
        var listed = rooms.lobby().rooms();

        assertThat(listed).hasSize(1);
        LobbyRoomState house = listed.get(0);
        assertThat(house.name()).isEqualTo("House Table");
        assertThat(house.maxSeats()).isEqualTo(RoomConfig.PUBLIC_SEATS);
        assertThat(house.memberCount()).isZero();
        assertThat(rooms.room(house.roomId())).isPresent();
    }

    @Test
    void theConfiguredAllowListIsTheOnlyRouteToModeratorStanding() {
        assertThat(moderators.isModerator(google("yashparth.gupta@gmail.com"))).isTrue();
        assertThat(moderators.isModerator(google("someone.else@gmail.com"))).isFalse();
        assertThat(moderators.isModerator(
                new SessionUserPrincipal(UUID.randomUUID(), AuthProvider.DEV, "Alice", null))).isFalse();
    }

    private static SessionUserPrincipal google(String email) {
        return new SessionUserPrincipal(UUID.randomUUID(), AuthProvider.GOOGLE, "Player", null, email);
    }
}
