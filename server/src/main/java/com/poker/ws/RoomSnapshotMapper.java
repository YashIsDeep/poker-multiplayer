package com.poker.ws;

import com.poker.room.LobbyRoomState;
import com.poker.room.LobbyState;
import com.poker.room.MemberState;
import com.poker.room.RoomState;
import com.poker.ws.protocol.LobbyRoomView;
import com.poker.ws.protocol.LobbySnapshot;
import com.poker.ws.protocol.RoomExitNotice;
import com.poker.ws.protocol.RoomMemberView;
import com.poker.ws.protocol.RoomSnapshot;
import com.poker.ws.protocol.SeatView;
import com.poker.ws.protocol.StateSyncView;
import com.poker.ws.protocol.ViewerRoomState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Allow-list mapper from immutable room projections to wire DTOs. */
@Component
public final class RoomSnapshotMapper {
    public LobbySnapshot lobby(LobbyState state) {
        Objects.requireNonNull(state, "state");
        List<LobbyRoomView> rooms = state.rooms().stream().map(this::lobbyRoom).toList();
        return new LobbySnapshot(state.revision(), state.serverNow(), rooms);
    }

    public RoomSnapshot forViewer(RoomState state, UUID viewerId, long membershipEpoch, Instant now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(viewerId, "viewerId");
        Objects.requireNonNull(now, "now");
        MemberState viewer = state.member(viewerId);
        if (viewer == null) {
            throw new IllegalArgumentException("viewer is not a room member");
        }
        List<RoomMemberView> members = state.members().stream().map(this::member).toList();
        List<SeatView> seats = new ArrayList<>(state.maxSeats());
        for (int seat = 0; seat < state.maxSeats(); seat++) {
            seats.add(new SeatView(seat, state.seats().get(seat)));
        }
        RoomMemberView viewerView = member(viewer);
        ViewerRoomState viewerState = new ViewerRoomState(
                viewerId,
                viewerView,
                viewer.seated(),
                viewer.seat(),
                viewer.seat() == null,
                viewer.seat() != null);
        return new RoomSnapshot(
                state.roomId(),
                state.joinCodeValue(),
                state.name(),
                state.maxSeats(),
                membershipEpoch,
                state.revision(),
                now,
                members,
                seats,
                viewerState,
                null);
    }

    public StateSyncView stateSync(LobbyState lobby, RoomState room, UUID viewerId, long membershipEpoch) {
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(viewerId, "viewerId");
        RoomSnapshot roomSnapshot = room == null ? null : forViewer(room, viewerId, membershipEpoch, lobby.serverNow());
        return new StateSyncView(lobby(lobby), membershipEpoch, roomSnapshot);
    }

    public RoomExitNotice exit(UUID roomId, long membershipEpoch, Instant now) {
        return new RoomExitNotice(roomId, RoomExitNotice.ExitReason.LEFT, membershipEpoch, now);
    }

    private LobbyRoomView lobbyRoom(LobbyRoomState room) {
        return new LobbyRoomView(
                room.roomId(),
                room.joinCode().value(),
                room.name(),
                room.maxSeats(),
                room.memberCount(),
                room.seatedCount(),
                room.createdAt());
    }

    private RoomMemberView member(MemberState member) {
        return new RoomMemberView(
                member.userId(),
                member.displayName(),
                member.avatarUrl(),
                member.joinedAt(),
                member.seat());
    }
}
