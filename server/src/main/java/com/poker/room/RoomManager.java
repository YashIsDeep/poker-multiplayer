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
import com.poker.engine.cards.Deck;
import com.poker.room.application.DeckFactory;
import java.security.SecureRandom;

/**
 * Authoritative in-memory room registry. The registry lock is always acquired
 * before a room lock; no publication or mapping happens while either is held.
 */
public final class RoomManager {
    private static final int MAX_JOIN_CODE_ATTEMPTS = 32;

    private final Clock clock;
    private final JoinCodeGenerator joinCodeGenerator;
    private final DeckFactory deckFactory;
    private final ReentrantLock registryLock = new ReentrantLock();
    private final Map<RoomId, PokerRoom> rooms = new HashMap<>();
    private final Map<JoinCode, RoomId> roomsByCode = new HashMap<>();
    private final Map<UUID, RoomId> roomsByUser = new HashMap<>();
    private final Map<RoomId, LobbyRoomState> lobbyRooms = new HashMap<>();
    private final Map<UUID, Long> membershipEpochs = new HashMap<>();
    private long lobbyRevision;

    public RoomManager() {
        this(Clock.systemUTC(), new JoinCodeGenerator(), () -> Deck.shuffled(new SecureRandom()));
    }

    public RoomManager(Clock clock, JoinCodeGenerator joinCodeGenerator) {
        this(clock, joinCodeGenerator, () -> Deck.shuffled(new SecureRandom()));
    }

    public RoomManager(Clock clock, JoinCodeGenerator joinCodeGenerator, DeckFactory deckFactory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.joinCodeGenerator = Objects.requireNonNull(joinCodeGenerator, "joinCodeGenerator");
        this.deckFactory = Objects.requireNonNull(deckFactory, "deckFactory");
    }

    public RoomChange create(MemberProfile profile, String rawName, int maxSeats) {
        return create(profile, rawName, maxSeats, RoomConfig.DEFAULT);
    }

    public RoomChange create(MemberProfile profile, String rawName, int maxSeats, RoomConfig config) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(config, "config");
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
            incrementEpoch(profile.userId());
            PokerRoom room = new PokerRoom(roomId, code, name, maxSeats, now, config, clock, deckFactory);
            room.members.put(profile.userId(), new PokerRoom.Member(profile, now, epoch(profile.userId())));
            room.assertInvariant();
            rooms.put(roomId, room);
            roomsByCode.put(code, roomId);
            roomsByUser.put(profile.userId(), roomId);
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
                incrementEpoch(profile.userId());
                room.members.put(profile.userId(), new PokerRoom.Member(profile, now, epoch(profile.userId())));
                roomsByUser.put(profile.userId(), roomId);
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
                    if (room.game.handIncludes(member.seat)) {
                        room.members.put(userId, member);
                        throw new RoomException(RoomError.HAND_IN_PROGRESS);
                    }
                    room.game.removeAtBoundary(member.seat);
                    room.seats[member.seat] = null;
                }
                roomsByUser.remove(userId);
                incrementEpoch(userId);
                incrementRoomRevision(room);
                incrementLobbyRevision();
                Set<UUID> recipients = memberIds(room);
                RoomState state = null;
                if (room.members.isEmpty()) {
                    room.closed = true;
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
        return seatMutation(userId, roomId, seat, 0);
    }

    public RoomChange takeSeat(UUID userId, UUID roomId, int seat, long buyIn) {
        return seatMutation(userId, roomId, seat, buyIn);
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
                boolean immediate = room.game.stand(member.seat);
                if (immediate) {
                    room.seats[member.seat] = null;
                    member.seat = null;
                }
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

    public RoomChange act(UUID userId, UUID roomId, long actionSeq, GameAction action, Long amount) {
        return gameplay(roomId, userId, room -> room.game.act(userId, actionSeq, action, amount));
    }

    public RoomChange sitOut(UUID userId, UUID roomId) {
        return gameplay(roomId, userId, room -> room.game.sitOut(requiredSeat(room, userId)));
    }

    public RoomChange sitIn(UUID userId, UUID roomId) {
        return gameplay(roomId, userId, room -> room.game.sitIn(requiredSeat(room, userId)));
    }

    public RoomChange rebuy(UUID userId, UUID roomId, long amount) {
        return gameplay(roomId, userId, room -> room.game.rebuy(requiredSeat(room, userId), amount));
    }

    public Optional<RoomChange> timeout(UUID roomId, long handId, long actionSeq, UUID actor) {
        return gameplayCallback(roomId, room -> room.game.timeout(handId, actionSeq, actor));
    }

    public Optional<RoomChange> startHand(
            UUID roomId,
            long lifecycleSeq,
            long scheduleGeneration) {
        return gameplayCallback(
                roomId,
                room -> room.game.startHand(lifecycleSeq, scheduleGeneration));
    }

    public Optional<RoomChange> pauseGameplay(UUID roomId, long scheduleGeneration) {
        return gameplayCallback(
                roomId,
                room -> room.game.pause(scheduleGeneration));
    }

    public Optional<RoomChange> recoverGameplay(UUID roomId, long scheduleGeneration) {
        return gameplayCallback(
                roomId,
                room -> room.game.recoverOrFail(scheduleGeneration));
    }

    public Set<UUID> pausedRoomIds() {
        registryLock.lock();
        try {
            java.util.HashSet<UUID> paused = new java.util.HashSet<>();
            for (PokerRoom room : rooms.values()) {
                room.lock.lock();
                try {
                    if (room.game.snapshot().tableStatus() == TableStatus.PAUSED) {
                        paused.add(room.id.value());
                    }
                } finally {
                    room.lock.unlock();
                }
            }
            return Set.copyOf(paused);
        } finally {
            registryLock.unlock();
        }
    }

    public Optional<RoomChange> advancePresentation(UUID roomId, long handId, long scheduleGeneration) {
        return gameplayCallback(roomId, room -> room.game.advancePresentation(handId, scheduleGeneration));
    }

    public Optional<RoomChange> nextHand(UUID roomId, long completedHandId, long scheduleGeneration) {
        return gameplayCallback(roomId, room -> {
            if (!room.game.nextHand(completedHandId, scheduleGeneration)) return false;
            for (PokerRoom.Member member : room.members.values()) {
                if (member.seat != null && !room.game.hasSeat(member.seat, member.userId)) {
                    room.seats[member.seat] = null;
                    member.seat = null;
                }
            }
            return true;
        });
    }

    private RoomChange gameplay(UUID rawRoomId, UUID userId, java.util.function.Consumer<PokerRoom> mutation) {
        Objects.requireNonNull(rawRoomId, "roomId"); Objects.requireNonNull(userId, "userId");
        RoomLease lease = gameplayLease(rawRoomId);
        PokerRoom room = lease.room();
        room.lock.lock();
        try {
            if (room.closed) throw new RoomException(RoomError.ROOM_NOT_FOUND);
            if (room.member(userId) == null) throw new RoomException(RoomError.NOT_A_ROOM_MEMBER);
            mutation.accept(room);
            incrementRoomRevision(room);
            room.assertInvariant();
            return gameplayChange(room, lease.lobby());
        } finally { room.lock.unlock(); }
    }

    private Optional<RoomChange> gameplayCallback(UUID rawRoomId, java.util.function.Predicate<PokerRoom> mutation) {
        RoomLease lease;
        try { lease = gameplayLease(rawRoomId); } catch (RoomException ex) { return Optional.empty(); }
        PokerRoom room = lease.room();
        room.lock.lock();
        try {
            if (room.closed || !mutation.test(room)) return Optional.empty();
            incrementRoomRevision(room);
            return Optional.of(gameplayChange(room, lease.lobby()));
        } finally { room.lock.unlock(); }
    }

    private RoomLease gameplayLease(UUID rawRoomId) {
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(new RoomId(rawRoomId));
            if (room == null) throw new RoomException(RoomError.ROOM_NOT_FOUND);
            return new RoomLease(room, lobbyStateLocked());
        } finally { registryLock.unlock(); }
    }

    private RoomChange gameplayChange(PokerRoom room, LobbyState lobby) {
        Set<UUID> recipients = Set.copyOf(room.members.keySet());
        Map<UUID, Long> epochs = new HashMap<>();
        for (PokerRoom.Member member : room.members.values()) epochs.put(member.userId, member.membershipEpoch);
        return new RoomChange(RoomChange.Kind.GAME_CHANGED, room.id.value(), room.snapshot(), lobby,
                recipients, epochs, null, 0, room.revision, lobby.revision());
    }

    private record RoomLease(PokerRoom room, LobbyState lobby) {}

    private static int requiredSeat(PokerRoom room, UUID userId) {
        PokerRoom.Member member = room.member(userId);
        if (member == null || member.seat == null) throw new GameException(GameError.NOT_SEATED);
        return member.seat;
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

    public Set<UUID> memberIds(UUID roomId) {
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(new RoomId(roomId));
            if (room == null) return Set.of();
            room.lock.lock();
            try { return Set.copyOf(room.members.keySet()); }
            finally { room.lock.unlock(); }
        } finally { registryLock.unlock(); }
    }

    public IdleGuard markRoomDisconnected(UUID roomId, Instant now) {
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(new RoomId(roomId));
            if (room == null) return new IdleGuard(0, now);
            room.lock.lock();
            try {
                if (room.allDisconnectedSince == null) {
                    room.allDisconnectedSince = now;
                    room.presenceGeneration = incrementExact(room.presenceGeneration, "presence generation");
                }
                return new IdleGuard(room.presenceGeneration, room.allDisconnectedSince);
            } finally { room.lock.unlock(); }
        } finally { registryLock.unlock(); }
    }

    public void markRoomConnected(UUID roomId) {
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(new RoomId(roomId));
            if (room == null) return;
            room.lock.lock();
            try {
                if (room.allDisconnectedSince != null) {
                    room.allDisconnectedSince = null;
                    room.idleExpiryPending = false;
                    room.presenceGeneration = incrementExact(room.presenceGeneration, "presence generation");
                }
            } finally { room.lock.unlock(); }
        } finally { registryLock.unlock(); }
    }

    public boolean idleGuardCurrent(
            UUID rawRoomId,
            long generation,
            Instant due,
            java.time.Duration grace) {
        RoomId roomId = new RoomId(rawRoomId);
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(roomId);
            if (room == null) return false;
            room.lock.lock();
            try {
                return room.presenceGeneration == generation
                        && room.allDisconnectedSince != null
                        && room.allDisconnectedSince.plus(grace).equals(due);
            } finally {
                room.lock.unlock();
            }
        } finally {
            registryLock.unlock();
        }
    }

    public Optional<RoomChange> expireIdleRoom(UUID rawRoomId, long generation, Instant due,
                                                Instant now, java.time.Duration grace) {
        RoomId roomId = new RoomId(rawRoomId);
        registryLock.lock();
        try {
            PokerRoom room = rooms.get(roomId);
            if (room == null) return Optional.empty();
            room.lock.lock();
            try {
                if (room.presenceGeneration != generation || room.allDisconnectedSince == null
                        || !room.allDisconnectedSince.plus(grace).equals(due)
                        || now.isBefore(due) || now.isBefore(room.allDisconnectedSince.plus(grace))) {
                    return Optional.empty();
                }
                GameState game = room.game.snapshot();
                if (game.tableStatus() == TableStatus.PAUSED
                        && (game.pausedSince() == null
                                || now.isBefore(game.pausedSince().plusSeconds(300)))) {
                    room.idleExpiryPending = true;
                    return Optional.empty();
                }
                if (!game.safeBoundary() && game.tableStatus() != TableStatus.FAILED
                        && game.tableStatus() != TableStatus.PAUSED) {
                    room.idleExpiryPending = true;
                    return Optional.empty();
                }
                Set<UUID> removed = Set.copyOf(room.members.keySet());
                Map<UUID, Long> epochs = new HashMap<>();
                for (UUID user : removed) { roomsByUser.remove(user); incrementEpoch(user); epochs.put(user, epoch(user)); }
                room.closed = true;
                rooms.remove(roomId); roomsByCode.remove(room.joinCode); lobbyRooms.remove(roomId);
                incrementLobbyRevision();
                return Optional.of(new RoomChange(RoomChange.Kind.IDLE_CLOSED, rawRoomId, null,
                        lobbyStateLocked(), removed, epochs, null, 0, room.revision, lobbyRevision));
            } finally { room.lock.unlock(); }
        } finally { registryLock.unlock(); }
    }

    public record IdleGuard(long generation, Instant since) {}

    private RoomChange seatMutation(UUID userId, UUID roomId, int seat, long requestedBuyIn) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        registryLock.lock();
        try {
            PokerRoom room = requiredMemberRoom(userId, roomId);
            long buyIn = requestedBuyIn == 0 ? room.config.minBuyIn() : requestedBuyIn;
            room.config.validateBuyIn(buyIn);
            if (seat < 0 || seat >= room.maxSeats) {
                throw new RoomException(RoomError.SEAT_OUT_OF_RANGE);
            }
            room.lock.lock();
            try {
                PokerRoom.Member member = room.member(userId);
                if (member.seat != null) {
                    if (member.seat == seat) {
                        if (!room.game.matchesSeatRequest(seat, userId, buyIn)) {
                            throw new RoomException(RoomError.ALREADY_SEATED);
                        }
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
                room.game.seat(seat, userId, buyIn);
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
