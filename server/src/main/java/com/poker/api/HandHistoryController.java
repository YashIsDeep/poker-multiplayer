package com.poker.api;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.room.RoomManager;
import com.poker.room.RoomState;
import com.poker.ws.HandHistoryMapper;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class HandHistoryController {
    private final RoomManager rooms;
    private final HandHistoryMapper mapper;

    public HandHistoryController(RoomManager rooms, HandHistoryMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @GetMapping("/api/rooms/{roomId}/hand-histories")
    public ResponseEntity<HandHistoryResponse> histories(
            @PathVariable UUID roomId,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentUserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        RoomState room = rooms.room(roomId).orElse(null);
        if (room == null) return ResponseEntity.notFound().build();
        if (room.member(principal.userId()) == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new HandHistoryResponse(
                        roomId, mapper.forViewer(room, principal.userId())));
    }
}
