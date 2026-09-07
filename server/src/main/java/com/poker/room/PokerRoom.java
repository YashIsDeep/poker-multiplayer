package com.poker.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Clock;
import com.poker.room.application.DeckFactory;

/** Mutable aggregate. Package-private so only RoomManager can mutate authoritative state. */
final class PokerRoom {
    /** Unseated members beyond the seat count; keeps spectate open on full tables while bounding fan-out. */
    static final int MAX_SPECTATORS = 10;

    final RoomId id;
    final JoinCode joinCode;
    final String name;
    final int maxSeats;
    final boolean privateTable;
    /** House tables outlive their last member: never closed, always listed. */
    final boolean permanent;
    final UUID creatorId;
    final Instant createdAt;
    RoomConfig config;
    final TableGame game;
    final ReentrantLock lock = new ReentrantLock();
    final Map<UUID, Member> members = new HashMap<>();
    final UUID[] seats;
    long revision = 1;

    boolean closed;
    long cleanupGeneration = 1;
    Instant allDisconnectedSince;
    Instant lastActivityAt;

    PokerRoom(RoomId id, JoinCode joinCode, String name, int maxSeats, boolean privateTable,
              UUID creatorId, Instant createdAt, RoomConfig config, Clock clock, DeckFactory decks) {
        this(id, joinCode, name, maxSeats, privateTable, false, creatorId, createdAt, config, clock, decks);
    }

    PokerRoom(RoomId id, JoinCode joinCode, String name, int maxSeats, boolean privateTable,
              boolean permanent, UUID creatorId, Instant createdAt, RoomConfig config, Clock clock,
              DeckFactory decks) {
        this.permanent = permanent;
        this.id = Objects.requireNonNull(id, "id");
        this.joinCode = Objects.requireNonNull(joinCode, "joinCode");
        this.name = Objects.requireNonNull(name, "name");
        this.maxSeats = maxSeats;
        this.privateTable = privateTable;
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.config = Objects.requireNonNull(config, "config");
        this.game = new TableGame(config, maxSeats, clock, decks);
        this.seats = new UUID[maxSeats];
        this.lastActivityAt = createdAt;
    }

    Member member(UUID userId) {
        return members.get(userId);
    }

    int memberCapacity() {
        return maxSeats + MAX_SPECTATORS;
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
                java.util.Arrays.asList(seats.clone()),
                config,
                game.snapshot(),
                lastActivityAt,
                privateTable,
                creatorId);
    }

    LobbyRoomState lobbyState() {
        int seated = 0;
        int connected = 0;
        for (Member member : members.values()) {
            if (member.seat != null) {
                seated++;
            }
            if (member.disconnectedSince == null) connected++;
        }
        return new LobbyRoomState(id, joinCode, name, maxSeats, members.size(), seated, connected, createdAt,
                game.ranked() ? "RANKED" : "CASUAL", game.rankedStatus());
    }

    void assertInvariant() {
        if ((!closed && !permanent && members.isEmpty()) || members.size() > memberCapacity() || revision <= 0) {
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
        final long membershipEpoch;
        Integer seat;
        Instant disconnectedSince;

        Member(MemberProfile profile, Instant joinedAt, long membershipEpoch) {
            this.userId = profile.userId();
            this.displayName = profile.displayName();
            this.avatarUrl = profile.avatarUrl();
            this.joinedAt = joinedAt;
            this.membershipEpoch = membershipEpoch;
        }

        MemberState snapshot() {
            return new MemberState(
                    userId, displayName, avatarUrl, joinedAt, seat,
                    disconnectedSince == null, disconnectedSince);
        }
    }
}
