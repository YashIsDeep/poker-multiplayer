package com.poker.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.MemberProfile;
import com.poker.room.RoomError;
import com.poker.room.RoomException;
import com.poker.room.RoomManager;
import com.poker.ws.protocol.CommandType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HardeningBehaviorTest {
    @Test
    void gameplayBudgetIsSharedByIdentityAndCannotStarveControlBudget() {
        HardeningProperties properties = new HardeningProperties();
        properties.setCommandRate(1);
        properties.setCommandBurst(2);
        properties.setControlRate(1);
        properties.setControlBurst(2);
        CommandAdmissionService admission = new CommandAdmissionService(properties);
        UUID user = UUID.randomUUID();

        assertThat(admission.admit(user, false).accepted()).isTrue();
        assertThat(admission.admit(user, false).accepted()).isTrue();
        assertThat(admission.admit(user, false).accepted()).isFalse();
        assertThat(admission.admit(user, true).accepted()).isTrue();
        assertThat(admission.admit(user, true).accepted()).isTrue();
        assertThat(admission.admit(user, true).accepted()).isFalse();
        assertThat(admission.admit(UUID.randomUUID(), false).accepted()).isTrue();
    }

    @Test
    void restartRecoveryRequiresTheClientSuppliedOldRoomId() {
        UUID user = UUID.randomUUID();
        RoomManager beforeRestart = new RoomManager();
        UUID oldRoomId = beforeRestart.create(
                new MemberProfile(user, "A", null), "Old", 2).roomId();

        RoomManager afterRestart = new RoomManager();
        assertThat(afterRestart.stateSync(user).room()).isNull();
        assertThatThrownBy(() -> afterRestart.roomSync(user, oldRoomId))
                .isInstanceOf(RoomException.class)
                .extracting(error -> ((RoomException) error).error())
                .isEqualTo(RoomError.ROOM_NOT_FOUND);
        assertThat(afterRestart.create(
                new MemberProfile(user, "A", null), "New", 2).roomId()).isNotNull();
    }

    @Test
    void telemetryUsesOnlyLowCardinalityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AbuseTelemetry telemetry = new AbuseTelemetry(meters);
        telemetry.command(CommandType.GAME_ACT, UUID.randomUUID(), true, 0);
        telemetry.command(CommandType.GAME_ACT, UUID.randomUUID(), false, 25);

        assertThat(meters.getMeters()).allSatisfy(meter -> {
            Set<String> keys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey()).collect(java.util.stream.Collectors.toSet());
            assertThat(keys).doesNotContain(
                    "userId", "roomId", "requestId", "sessionId", "joinCode", "ip");
        });
    }
}
