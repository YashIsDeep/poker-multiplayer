package com.poker.admin;

import com.poker.auth.LoopbackRequestGuard;
import com.poker.presence.UserSocketRegistry;
import com.poker.room.GameState;
import com.poker.room.MemberState;
import com.poker.room.RoomConfig;
import com.poker.room.RoomManager;
import com.poker.room.RoomState;
import com.poker.room.TablePlayerState;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only projection of live in-memory server state for the standalone
 * Admin Console. The room and connection views expose only membership,
 * seating, balances, and lifecycle metadata. The hand drilldown views
 * intentionally expose complete hand state, including every dealt card —
 * they exist for local debugging and are reachable only through this
 * loopback-guarded, disabled-by-default API.
 */
@RestController
@RequestMapping("/internal/admin/live")
@ConditionalOnProperty(prefix = "poker.admin.live", name = "enabled", havingValue = "true")
public class AdminLiveController {
    static final String TOKEN_HEADER = "X-Admin-Token";

    private final RoomManager roomManager;
    private final UserSocketRegistry socketRegistry;
    private final AdminLiveProperties properties;
    private final Clock clock;
    private final Instant startedAt;

    public AdminLiveController(
            RoomManager roomManager,
            UserSocketRegistry socketRegistry,
            AdminLiveProperties properties,
            Clock clock) {
        this.roomManager = roomManager;
        this.socketRegistry = socketRegistry;
        this.properties = properties;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @GetMapping("/overview")
    public Overview overview(HttpServletRequest request) {
        requireLocalAdmin(request);
        List<RoomState> rooms = roomSnapshots();
        int members = rooms.stream().mapToInt(RoomState::memberCount).sum();
        int seated = rooms.stream().mapToInt(RoomState::seatedCount).sum();
        Instant now = clock.instant();
        return new Overview(
                now,
                Duration.between(startedAt, now).toSeconds(),
                rooms.size(),
                members,
                seated,
                socketRegistry.total(),
                roomManager.lobby().revision(),
                roomManager.pausedRoomIds().size());
    }

    @GetMapping("/rooms")
    public List<RoomSummary> rooms(HttpServletRequest request) {
        requireLocalAdmin(request);
        return roomSnapshots().stream()
                .sorted(Comparator.comparing(RoomState::createdAt).thenComparing(RoomState::roomId))
                .map(AdminLiveController::summarize)
                .toList();
    }

    @GetMapping("/rooms/{roomId}")
    public RoomDetail room(HttpServletRequest request, @PathVariable UUID roomId) {
        requireLocalAdmin(request);
        return detail(requiredRoom(roomId));
    }

    private RoomState requiredRoom(UUID roomId) {
        return roomManager.room(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "room not found"));
    }

    @GetMapping("/rooms/{roomId}/hand")
    public AdminHandViews.LiveHandView liveHand(HttpServletRequest request, @PathVariable UUID roomId) {
        requireLocalAdmin(request);
        return AdminHandViews.liveHand(requiredRoom(roomId));
    }

    @GetMapping("/rooms/{roomId}/hands")
    public List<AdminHandViews.HandSummaryView> hands(HttpServletRequest request, @PathVariable UUID roomId) {
        requireLocalAdmin(request);
        return AdminHandViews.handSummaries(requiredRoom(roomId));
    }

    @GetMapping("/rooms/{roomId}/hands/{handId}")
    public AdminHandViews.HandDetailView handDetail(
            HttpServletRequest request, @PathVariable UUID roomId, @PathVariable long handId) {
        requireLocalAdmin(request);
        AdminHandViews.HandDetailView detail = AdminHandViews.handDetail(requiredRoom(roomId), handId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no retained hand history with that id");
        }
        return detail;
    }

    @GetMapping("/connections")
    public Connections connections(HttpServletRequest request) {
        requireLocalAdmin(request);
        Map<UUID, Integer> counts = socketRegistry.socketCountsByUser();
        List<UserConnections> users = counts.entrySet().stream()
                .map(entry -> {
                    UUID roomId = roomManager.currentRoom(entry.getKey()).orElse(null);
                    return new UserConnections(
                            entry.getKey(),
                            entry.getValue(),
                            roomId,
                            displayName(entry.getKey(), roomId));
                })
                .sorted(Comparator.comparing(UserConnections::userId))
                .toList();
        return new Connections(socketRegistry.total(), users);
    }

    private List<RoomState> roomSnapshots() {
        List<RoomState> snapshots = new ArrayList<>();
        for (UUID roomId : roomManager.roomIds()) {
            roomManager.room(roomId).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    private String displayName(UUID userId, UUID roomId) {
        if (roomId == null) return null;
        return roomManager.room(roomId)
                .map(room -> room.member(userId))
                .map(MemberState::displayName)
                .orElse(null);
    }

    private static RoomSummary summarize(RoomState room) {
        GameState game = room.game();
        return new RoomSummary(
                room.roomId(),
                room.joinCodeValue(),
                room.name(),
                room.privateTable(),
                room.maxSeats(),
                room.memberCount(),
                room.seatedCount(),
                room.connectedMemberCount(),
                game.tableStatus().name(),
                game.handId(),
                room.revision(),
                room.createdAt(),
                room.lastActivityAt());
    }

    private RoomDetail detail(RoomState room) {
        GameState game = room.game();
        List<MemberDetail> members = room.members().stream()
                .map(member -> {
                    TablePlayerState player = game.players().get(member.userId());
                    return new MemberDetail(
                            member.userId(),
                            member.displayName(),
                            member.seat(),
                            member.connected(),
                            member.disconnectedSince(),
                            member.joinedAt(),
                            player == null ? null : player.stack(),
                            player == null ? 0 : player.rebuyCount(),
                            socketRegistry.count(member.userId()));
                })
                .toList();
        RoomConfig config = room.config();
        return new RoomDetail(
                room.roomId(),
                room.joinCodeValue(),
                room.name(),
                room.privateTable(),
                room.maxSeats(),
                room.creatorId(),
                room.createdAt(),
                room.lastActivityAt(),
                room.revision(),
                new ConfigDetail(
                        config.smallBlind(),
                        config.bigBlind(),
                        config.minBuyIn(),
                        config.maxBuyIn(),
                        config.actionTimeoutSeconds(),
                        config.startingStack(),
                        config.rebuysEnabled(),
                        config.rebuyStack(),
                        config.rebuyDelayHands()),
                new GameDetail(
                        game.tableStatus().name(),
                        game.handId(),
                        game.actionSeq(),
                        game.deadline(),
                        game.pausedSince(),
                        game.nextHandAt(),
                        game.safeBoundary()),
                members);
    }

    /**
     * The endpoint is unauthenticated in the web session sense, so it accepts
     * only direct loopback peers and a mandatory shared token header. The
     * transport evidence survives Spring's forwarded-header request wrapper.
     */
    private void requireLocalAdmin(HttpServletRequest request) {
        if (!LoopbackRequestGuard.strictlyLocal(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "direct loopback clients only");
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !MessageDigest.isEqual(
                properties.token().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing or invalid admin token");
        }
    }

    public record Overview(
            Instant serverTime,
            long uptimeSeconds,
            int roomCount,
            int memberCount,
            int seatedCount,
            int connectedSockets,
            long lobbyRevision,
            int pausedRoomCount) {}

    public record RoomSummary(
            UUID roomId,
            String joinCode,
            String name,
            boolean privateTable,
            int maxSeats,
            int memberCount,
            int seatedCount,
            int connectedCount,
            String tableStatus,
            long handId,
            long revision,
            Instant createdAt,
            Instant lastActivityAt) {}

    public record RoomDetail(
            UUID roomId,
            String joinCode,
            String name,
            boolean privateTable,
            int maxSeats,
            UUID creatorId,
            Instant createdAt,
            Instant lastActivityAt,
            long revision,
            ConfigDetail config,
            GameDetail game,
            List<MemberDetail> members) {}

    public record ConfigDetail(
            long smallBlind,
            long bigBlind,
            long minBuyIn,
            long maxBuyIn,
            int actionTimeoutSeconds,
            long startingStack,
            boolean rebuysEnabled,
            long rebuyStack,
            int rebuyDelayHands) {}

    public record GameDetail(
            String tableStatus,
            long handId,
            long actionSeq,
            Instant deadline,
            Instant pausedSince,
            Instant nextHandAt,
            boolean safeBoundary) {}

    public record MemberDetail(
            UUID userId,
            String displayName,
            Integer seat,
            boolean connected,
            Instant disconnectedSince,
            Instant joinedAt,
            Long stack,
            int rebuyCount,
            int sockets) {}

    public record Connections(int totalSockets, List<UserConnections> users) {}

    public record UserConnections(UUID userId, int sockets, UUID roomId, String displayName) {}
}
