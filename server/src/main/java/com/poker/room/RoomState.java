package com.poker.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable room projection. It deliberately has no Spring, security, or wire types. */
public record RoomState(
        RoomId id,
        JoinCode joinCode,
        String name,
        int maxSeats,
        Instant createdAt,
        long revision,
        List<MemberState> members,
        List<UUID> seats) {
    public RoomState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(joinCode, "joinCode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(seats, "seats");
        if (maxSeats < 2 || maxSeats > 9 || revision <= 0 || members.isEmpty()
                || members.size() > maxSeats || seats.size() != maxSeats) {
            throw new IllegalArgumentException("invalid room projection");
        }
        if (members.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("room members cannot contain null");
        }
        members = members.stream()
                .sorted(Comparator.comparing(MemberState::joinedAt).thenComparing(MemberState::userId))
                .toList();
        if (members.size() != new java.util.HashSet<>(members.stream().map(MemberState::userId).toList()).size()) {
            throw new IllegalArgumentException("duplicate room members");
        }
        seats = Collections.unmodifiableList(new ArrayList<>(seats));
        java.util.Set<UUID> seatedUsers = new java.util.HashSet<>();
        for (int seat = 0; seat < seats.size(); seat++) {
            UUID userId = seats.get(seat);
            if (userId == null) {
                continue;
            }
            if (!seatedUsers.add(userId)) {
                throw new IllegalArgumentException("a user cannot occupy multiple seats");
            }
            MemberState member = members.stream()
                    .filter(candidate -> candidate.userId().equals(userId))
                    .findFirst()
                    .orElse(null);
            if (member == null || !Integer.valueOf(seat).equals(member.seat())) {
                throw new IllegalArgumentException("room seat projection is inconsistent");
            }
        }
        for (MemberState member : members) {
            if (member.seat() != null && (member.seat() >= maxSeats
                    || !member.userId().equals(seats.get(member.seat())))) {
                throw new IllegalArgumentException("member seat projection is inconsistent");
            }
        }
    }

    public UUID roomId() {
        return id.value();
    }

    public String joinCodeValue() {
        return joinCode.value();
    }

    public int memberCount() {
        return members.size();
    }

    public int seatedCount() {
        return (int) members.stream().filter(MemberState::seated).count();
    }

    public MemberState member(UUID userId) {
        return members.stream().filter(member -> member.userId().equals(userId)).findFirst().orElse(null);
    }
}
