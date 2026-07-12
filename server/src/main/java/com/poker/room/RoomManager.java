package com.poker.room;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authoritative in-memory room registry. The registry lock is always acquired
 * before a room lock; no publication or mapping happens while either is held.
 */
public final class RoomManager {
    private static final int MAX_JOIN_CODE_ATTEMPTS = 32;

    private final Clock clock;
    private final JoinCodeGenerator joinCodeGenerator;
    private final ReentrantLock registryLock = new ReentrantLock();
    private final Map<RoomId, PokerRoom> rooms = new HashMap<>();
    private final Map<JoinCode, RoomId> roomsByCode = new HashMap<>();
    private final Map<UUID, RoomId> roomsByUser = new HashMap<>();
    private final Map<RoomId, LobbyRoomState> lobbyRooms = new HashMap<>();
    private final Map<UUID, Long> membershipEpochs = new HashMap<>();
    private long lobbyRevision;

    public RoomManager() {
        this(Clock.systemUTC(), new JoinCodeGenerator());
    }

    public RoomManager(Clock clock, JoinCodeGenerator joinCodeGenerator) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.joinCodeGenerator = Objects.requireNonNull(joinCodeGenerator, "joinCodeGenerator");
    }

    public RoomChange create(MemberProfile profile, String rawName, int maxSeats) {
        Objects.requireNonNull(profile, "profile");
        String name = normalizeName(rawName);
        validateMaxSeats(maxSeats);
        registryLock.lock();
        try {
            if (roomsByUser.containsKey(profile.userId())) {
                throw new RoomException(RoomError.ALREADY_IN_ANOTHER_ROOM);
            }
            RoomId roomId = uniqueRoomId();
            JoinCode code = allocateCode();
            Instant now = clock.instant();
            PokerRoom room = new PokerRoom(roomId, code, name, maxSeats, now);
            room.members.put(profile.userId(), new PokerRoom.Member(profile, now));
            room.assertInvariant();
            rooms.put(roomId, room);
            roomsByCode.put(code, roomId);
            roomsByUser.put(profile.userId(), roomId);
            incrementEpoch(profile.userId());
            incrementLobbyRevision();
            updateLobbyEntry(room);
            return change(
                    RoomChange.Kind.CREATED,
                    room,
                    Set.of(profile.userId()),
                    null,
                    0,
                    Map.of(profile.userId(), membershipEpochs.get(profile.userId())));
        } finally {
            registryLock.unlock();
        }
    }

    public RoomChange create(UUID userId, String displayName, String avatarUrl, String rawName, int maxSeats) {
        return create(new MemberProfile(userId, displayName, avatarUrl), rawName, maxSeats);
    }

    public RoomChange join(MemberProfile profile, String rawJoinCode) {
        Objects.requireNonNull(profile, "profile");
        JoinCode code = parseJoinCode(rawJoinCode);
        registryLock.lock();
        try {
            RoomId roomId = roomsByCode.get(code);
            if (roomId == null) {
                throw new RoomException(RoomError.ROOM_NOT_FOUND);
            }
            RoomId current = roomsByUser.get(profile.userId());
            if (current != null && !current.equals(roomId)) {
                throw new RoomException(RoomError.ALREADY_IN_ANOTHER_ROOM);
            }
            PokerRoom room = rooms.get(roomId);
            if (room == null) {
                throw new RoomException(RoomError.ROOM_NOT_FOUND);
            }
            room.lock.lock();
            try {
                if (room.members.containsKey(profile.userId())) {
                    return change(
                            RoomChange.Kind.JOINED,
                            room,
                            Set.of(profile.userId()),
                            null,
                            0,
                            Map.of(profile.userId(), epoch(profile.userId())));
                }
                if (room.members.size() >= room.maxSeats) {
                    throw new RoomException(RoomError.ROOM_FULL);
                }
                Instant now = clock.instant();
                room.members.put(profile.userId(), new PokerRoom.Member(profile, now));
                roomsByUser.put(profile.userId(), roomId);
                incrementEpoch(profile.userId());
                incrementRoomRevision(room);
                incrementLobbyRevision();
                room.assertInvariant();
                updateLobbyEntry(room);
                return change(
                        RoomChange.Kind.JOINED,
                        room,
                        memberIds(room),
                        null,
                        0,
                        epochsFor(room));
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public RoomChange join(MemberProfile profile, JoinCode joinCode) {
        Objects.requireNonNull(joinCode, "joinCode");
        return join(profile, joinCode.value());
    }

    public RoomChange join(UUID userId, String displayName, String avatarUrl, String rawJoinCode) {
        return join(new MemberProfile(userId, displayName, avatarUrl), rawJoinCode);
    }

    public RoomChange leave(UUID userId, UUID requestedRoomId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestedRoomId, "requestedRoomId");
        RoomId requested = new RoomId(requestedRoomId);
        registryLock.lock();
        try {
            RoomId current = roomsByUser.get(userId);
            if (current == null) {
                return unchangedLeave(userId, requestedRoomId);
            }
            if (!requested.equals(current)) {
                throw new RoomException(RoomError.NOT_A_ROOM_MEMBER);
            }
            PokerRoom room = rooms.get(requested);
            if (room == null) {
                throw new IllegalStateException("membership index points to a missing room");
            }
            room.lock.lock();
            try {
                PokerRoom.Member member = room.members.remove(userId);
                if (member == null) {
                    throw new RoomException(RoomError.NOT_A_ROOM_MEMBER);
                }
                if (member.seat != null) {
                    room.seats[member.seat] = null;
                }
                roomsByUser.remove(userId);
                incrementEpoch(userId);
                incrementRoomRevision(room);
                incrementLobbyRevision();
                Set<UUID> recipients = memberIds(room);
                RoomState state = null;
                if (room.members.isEmpty()) {
                    rooms.remove(room.id);
                    roomsByCode.remove(room.joinCode);
                    lobbyRooms.remove(room.id);
                } else {
                    room.assertInvariant();
                    updateLobbyEntry(room);
                    state = room.snapshot();
                }
                Map<UUID, Long> epochs = new HashMap<>();
                for (UUID recipient : recipients) {
                    epochs.put(recipient, epoch(recipient));
                }
                epochs.put(userId, epoch(userId));
                return new RoomChange(
                        RoomChange.Kind.LEFT,
                        requestedRoomId,
                        state,
                        lobbyStateLocked(),
                        recipients,
                        epochs,
                        userId,
                        epoch(userId),
                        room.revision,
                        lobbyRevision);
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public RoomChange leave(UUID userId, RoomId roomId) {
        Objects.requireNonNull(roomId, "roomId");
        return leave(userId, roomId.value());
    }

    public RoomChange takeSeat(UUID userId, UUID roomId, int seat) {
        return seatMutation(userId, roomId, seat);
    }

    public RoomChange takeSeat(UUID userId, RoomId roomId, int seat) {
        Objects.requireNonNull(roomId, "roomId");
        return takeSeat(userId, roomId.value(), seat);
    }

    public RoomChange stand(UUID userId, UUID roomId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        registryLock.lock();
        try {
            PokerRoom room = requiredMemberRoom(userId, roomId);
            room.lock.lock();
            try {
                PokerRoom.Member member = room.member(userId);
                if (member.seat == null) {
                    return change(
                            RoomChange.Kind.STOOD,
                            room,
                            Set.of(userId),
                            null,
                            0,
                            Map.of(userId, epoch(userId)));
                }
                room.seats[member.seat] = null;
                member.seat = null;
                incrementRoomRevision(room);
                incrementLobbyRevision();
                room.assertInvariant();
                updateLobbyEntry(room);
                return change(
                        RoomChange.Kind.STOOD,
                        room,
                        memberIds(room),
                        null,
                        0,
                        epochsFor(room));
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public RoomChange stand(UUID userId, RoomId roomId) {
        Objects.requireNonNull(roomId, "roomId");
        return stand(userId, roomId.value());
    }

    public RoomChange roomSync(UUID userId, UUID roomId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        registryLock.lock();
        try {
            PokerRoom room = requiredMemberRoom(userId, roomId);
            room.lock.lock();
            try {
                return change(
                        RoomChange.Kind.ROOM_SYNC,
                        room,
                        Set.of(userId),
                        null,
                        0,
                        Map.of(userId, epoch(userId)));
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public RoomChange roomSync(UUID userId, RoomId roomId) {
        Objects.requireNonNull(roomId, "roomId");
        return roomSync(userId, roomId.value());
    }

    public RoomChange stateSync(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        registryLock.lock();
        try {
            RoomState roomState = null;
            RoomId current = roomsByUser.get(userId);
            if (current != null) {
                PokerRoom room = rooms.get(current);
                if (room != null) {
                    room.lock.lock();
                    try {
                        roomState = room.snapshot();
                    } finally {
                        room.lock.unlock();
                    }
                }
            }
            return new RoomChange(
                    RoomChange.Kind.STATE_SYNC,
                    current == null ? null : current.value(),
                    roomState,
                    lobbyStateLocked(),
                    Set.of(userId),
                    Map.of(userId, epoch(userId)),
                    null,
                    epoch(userId),
                    roomState == null ? 0 : roomState.revision(),
                    lobbyRevision);
        } finally {
            registryLock.unlock();
        }
    }

    public StateSyncState state(UUID userId) {
        RoomChange change = stateSync(userId);
        return new StateSyncState(
                change.lobby(),
                change.membershipEpochs().getOrDefault(userId, 0L),
                change.room());
    }

    public Optional<RoomState> room(UUID roomId) {
        Objects.requireNonNull(roomId, "roomId");
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(new RoomId(roomId));
            if (room == null) {
                return Optional.empty();
            }
            room.lock.lock();
            try {
                return Optional.of(room.snapshot());
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public LobbyState lobby() {
        registryLock.lock();
        try {
            return lobbyStateLocked();
        } finally {
            registryLock.unlock();
        }
    }

    public Optional<UUID> currentRoom(UUID userId) {
        registryLock.lock();
        try {
            RoomId roomId = roomsByUser.get(userId);
            return roomId == null ? Optional.empty() : Optional.of(roomId.value());
        } finally {
            registryLock.unlock();
        }
    }

    public long membershipEpoch(UUID userId) {
        registryLock.lock();
        try {
            return epoch(userId);
        } finally {
            registryLock.unlock();
        }
    }

    public int roomCount() {
        registryLock.lock();
        try {
            return rooms.size();
        } finally {
            registryLock.unlock();
        }
    }

    private RoomChange seatMutation(UUID userId, UUID roomId, int seat) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        registryLock.lock();
        try {
            PokerRoom room = requiredMemberRoom(userId, roomId);
            if (seat < 0 || seat >= room.maxSeats) {
                throw new RoomException(RoomError.SEAT_OUT_OF_RANGE);
            }
            room.lock.lock();
            try {
                PokerRoom.Member member = room.member(userId);
                if (member.seat != null) {
                    if (member.seat == seat) {
                        return change(
                                RoomChange.Kind.SEAT_TAKEN,
                                room,
                                Set.of(userId),
                                null,
                                0,
                                Map.of(userId, epoch(userId)));
                    }
                    throw new RoomException(RoomError.ALREADY_SEATED);
                }
                UUID occupant = room.seats[seat];
                if (occupant != null) {
                    throw new RoomException(RoomError.SEAT_OCCUPIED);
                }
                room.seats[seat] = userId;
                member.seat = seat;
                incrementRoomRevision(room);
                incrementLobbyRevision();
                room.assertInvariant();
                updateLobbyEntry(room);
                return change(
                        RoomChange.Kind.SEAT_TAKEN,
                        room,
                        memberIds(room),
                        null,
                        0,
                        epochsFor(room));
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    private PokerRoom requiredMemberRoom(UUID userId, UUID roomId) {
        PokerRoom room = rooms.get(new RoomId(roomId));
        if (room == null) {
            throw new RoomException(RoomError.ROOM_NOT_FOUND);
        }
        RoomId current = roomsByUser.get(userId);
        if (!new RoomId(roomId).equals(current) || !room.members.containsKey(userId)) {
            throw new RoomException(RoomError.NOT_A_ROOM_MEMBER);
        }
        return room;
    }

    private RoomChange change(
            RoomChange.Kind kind,
            PokerRoom room,
            Set<UUID> recipients,
            UUID exitedUserId,
            long exitedEpoch,
            Map<UUID, Long> epochs) {
        return new RoomChange(
                kind,
                room.id.value(),
                room.snapshot(),
                lobbyStateLocked(),
                recipients,
                epochs,
                exitedUserId,
                exitedEpoch,
                room.revision,
                lobbyRevision);
    }

    private RoomChange unchangedLeave(UUID userId, UUID requestedRoomId) {
        return new RoomChange(
                RoomChange.Kind.LEFT,
                requestedRoomId,
                null,
                lobbyStateLocked(),
                Set.of(),
                Map.of(userId, epoch(userId)),
                null,
                epoch(userId),
                0,
                lobbyRevision);
    }

    private Set<UUID> memberIds(PokerRoom room) {
        return Set.copyOf(room.members.keySet());
    }

    private Map<UUID, Long> epochsFor(PokerRoom room) {
        Map<UUID, Long> result = new HashMap<>();
        for (UUID userId : room.members.keySet()) {
            result.put(userId, epoch(userId));
        }
        return result;
    }

    private LobbyState lobbyStateLocked() {
        List<LobbyRoomState> summaries = lobbyRooms.values().stream()
                .sorted(Comparator.comparing(LobbyRoomState::createdAt).thenComparing(LobbyRoomState::roomId))
                .toList();
        return new LobbyState(lobbyRevision, clock.instant(), summaries);
    }

    private void updateLobbyEntry(PokerRoom room) {
        lobbyRooms.put(room.id, room.lobbyState());
    }

    private JoinCode allocateCode() {
        for (int attempt = 0; attempt < MAX_JOIN_CODE_ATTEMPTS; attempt++) {
            JoinCode candidate = joinCodeGenerator.next();
            if (!roomsByCode.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new RoomException(RoomError.JOIN_CODE_EXHAUSTED);
    }

    private RoomId uniqueRoomId() {
        RoomId roomId;
        do {
            roomId = RoomId.random();
        } while (rooms.containsKey(roomId));
        return roomId;
    }

    private static String normalizeName(String rawName) {
        try {
            return RoomNamePolicy.normalize(rawName);
        } catch (IllegalArgumentException ex) {
            throw new RoomException(RoomError.INVALID_ROOM_NAME, ex);
        }
    }

    private static void validateMaxSeats(int maxSeats) {
        if (maxSeats < 2 || maxSeats > 9) {
            throw new RoomException(RoomError.INVALID_MAX_SEATS);
        }
    }

    private static JoinCode parseJoinCode(String rawJoinCode) {
        try {
            return JoinCode.parse(rawJoinCode);
        } catch (RuntimeException ex) {
            throw new RoomException(RoomError.INVALID_JOIN_CODE, ex);
        }
    }

    private long epoch(UUID userId) {
        return membershipEpochs.getOrDefault(userId, 0L);
    }

    private void incrementEpoch(UUID userId) {
        membershipEpochs.put(userId, incrementExact(epoch(userId), "membership epoch"));
    }

    private void incrementRoomRevision(PokerRoom room) {
        room.revision = incrementExact(room.revision, "room revision");
    }

    private void incrementLobbyRevision() {
        lobbyRevision = incrementExact(lobbyRevision, "lobby revision");
    }

    private static long incrementExact(long value, String label) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException(label + " overflow", ex);
        }
    }
}
