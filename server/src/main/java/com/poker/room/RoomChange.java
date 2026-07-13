package com.poker.room;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable committed change passed to the transport adapter after all locks release. */
public record RoomChange(
        Kind kind,
        UUID roomId,
        RoomState room,
        LobbyState lobby,
        Set<UUID> roomRecipients,
        Map<UUID, Long> membershipEpochs,
        UUID exitedUserId,
        long exitedMembershipEpoch,
        long roomRevision,
        long lobbyRevision) {
    public enum Kind {
        CREATED,
        JOINED,
        LEFT,
        ROOM_SYNC,
        SEAT_TAKEN,
        STOOD,
        GAME_CHANGED,
        PRESENCE_CHANGED,
        KEEP_ALIVE,
        DISCONNECTED_MEMBER_EVICTED,
        IDLE_CLOSED,
        STATE_SYNC
    }

    public RoomChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(roomRecipients, "roomRecipients");
        Objects.requireNonNull(membershipEpochs, "membershipEpochs");
        roomRecipients = Set.copyOf(roomRecipients);
        membershipEpochs = Map.copyOf(membershipEpochs);
        if (roomRevision < 0 || lobbyRevision < 0) {
            throw new IllegalArgumentException("revisions cannot be negative");
        }
    }

    public List<UUID> recipients() {
        return roomRecipients.stream().sorted().toList();
    }

    public boolean hasExitNotice() {
        return exitedUserId != null;
    }
}
