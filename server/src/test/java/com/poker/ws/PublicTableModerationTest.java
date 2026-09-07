package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.auth.AuthProvider;
import com.poker.auth.ModeratorPolicy;
import com.poker.auth.SessionUserPrincipal;
import com.poker.room.JoinCode;
import com.poker.room.JoinCodeGenerator;
import com.poker.room.RoomManager;
import com.poker.room.application.PublicationReport;
import com.poker.room.application.RoomCommandService;
import com.poker.ws.protocol.CommandAck;
import com.poker.ws.protocol.CommandEnvelope;
import com.poker.ws.protocol.CreateRoomRequest;
import com.poker.ws.protocol.ErrorCode;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Public tables are the shared lobby, so only a moderator may add one. */
class PublicTableModerationTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String MODERATOR_EMAIL = "yashparth.gupta@gmail.com";

    @Test
    void anOrdinaryPlayerCannotCreateAPublicTable() {
        RoomMessageController controller = controller();

        CommandAck ack = controller.create(
                envelope(publicTable("Anyone's Table")), principal("someone.else@gmail.com"));

        assertThat(ack.ok()).isFalse();
        assertThat(ack.error()).isEqualTo(ErrorCode.NOT_A_MODERATOR);
    }

    @Test
    void anOrdinaryPlayerCanStillCreateAPrivateTable() {
        RoomMessageController controller = controller();

        CommandAck ack = controller.create(
                envelope(privateTable("Friends Only")), principal("someone.else@gmail.com"));

        assertThat(ack.ok()).isTrue();
        assertThat(ack.roomId()).isNotNull();
    }

    @Test
    void theModeratorCanCreateAPublicTable() {
        RoomMessageController controller = controller();

        CommandAck ack = controller.create(
                envelope(publicTable("House Rules")), principal(MODERATOR_EMAIL));

        assertThat(ack.ok()).isTrue();
        assertThat(ack.roomId()).isNotNull();
    }

    private static RoomMessageController controller() {
        AtomicInteger sequence = new AtomicInteger(1);
        RoomManager rooms = new RoomManager(CLOCK, new JoinCodeGenerator(
                () -> JoinCode.of("ABCDE" + sequence.incrementAndGet())));
        return new RoomMessageController(
                new RoomCommandService(rooms, change -> PublicationReport.none()),
                new RoomSnapshotMapper(),
                null,
                ModeratorPolicy.of(List.of(MODERATOR_EMAIL), false));
    }

    private static CreateRoomRequest publicTable(String name) {
        return new CreateRoomRequest(name, 6);
    }

    private static CreateRoomRequest privateTable(String name) {
        return new CreateRoomRequest(name, 6, true, 0, 0, 0, 0, 0, 0, false, 0, 0);
    }

    private static CommandEnvelope<CreateRoomRequest> envelope(CreateRoomRequest payload) {
        return new CommandEnvelope<>(UUID.randomUUID().toString(), payload);
    }

    private static Principal principal(String email) {
        SessionUserPrincipal user = new SessionUserPrincipal(
                UUID.randomUUID(), AuthProvider.GOOGLE, "Player", null, email);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities());
    }
}
