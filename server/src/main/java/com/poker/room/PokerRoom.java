package com.poker.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Mutable aggregate. Package-private so only RoomManager can mutate authoritative state. */
final class PokerRoom {
    final RoomId id;
    final JoinCode joinCode;
    final String name;
    final int maxSeats;
    final Instant createdAt;
    final ReentrantLock lock = new ReentrantLock();
    final Map<UUID, Member> members = new HashMap<>();
    final UUID[] seats;
    long revision = 1;

    PokerRoom(RoomId id, JoinCode joinCode, String name, int maxSeats, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.joinCode = Objects.requireNonNull(joinCode, "joinCode");
        this.name = Objects.requireNonNull(name, "name");
        this.maxSeats = maxSeats;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.seats = new UUID[maxSeats];
    }

    Member member(UUID userId) {
        return members.get(userId);
    }

    RoomState snapshot() {
        List<MemberState> memberStates = members.values().stream()
                .map(Member::snapshot)
                .sorted(java.util.Comparator.comparing(MemberState::joinedAt).thenComparing(MemberState::userId))
                .toList();
        return new RoomState(
                id,
                joinCode,
                name,
                maxSeats,
                createdAt,
                revision,
                memberStates,
                java.util.Arrays.asList(seats.clone()));
    }

    LobbyRoomState lobbyState() {
        int seated = 0;
        for (Member member : members.values()) {
            if (member.seat != null) {
                seated++;
            }
        }
        return new LobbyRoomState(id, joinCode, name, maxSeats, members.size(), seated, createdAt);
    }

    void assertInvariant() {
        if (members.isEmpty() || members.size() > maxSeats || revision <= 0) {
            throw new IllegalStateException("room invariant violated");
        }
        for (int seat = 0; seat < seats.length; seat++) {
            UUID userId = seats[seat];
            if (userId == null) {
                continue;
            }
            Member member = members.get(userId);
            if (member == null || !Integer.valueOf(seat).equals(member.seat)) {
                throw new IllegalStateException("seat invariant violated");
            }
        }
        for (Member member : members.values()) {
            if (member.seat != null && (member.seat < 0 || member.seat >= seats.length
                    || !member.userId.equals(seats[member.seat]))) {
                throw new IllegalStateException("member seat invariant violated");
            }
        }
    }

    static final class Member {
        final UUID userId;
        final String displayName;
        final String avatarUrl;
        final Instant joinedAt;
        Integer seat;

        Member(MemberProfile profile, Instant joinedAt) {
            this.userId = profile.userId();
            this.displayName = profile.displayName();
            this.avatarUrl = profile.avatarUrl();
            this.joinedAt = joinedAt;
        }

        MemberState snapshot() {
            return new MemberState(userId, displayName, avatarUrl, joinedAt, seat);
        }
    }
}
