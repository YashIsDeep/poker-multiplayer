package com.poker.api;

import com.poker.ws.protocol.HandHistoryView;
import java.util.List;
import java.util.UUID;

public record HandHistoryResponse(UUID roomId, List<HandHistoryView> hands) {
    public HandHistoryResponse {
        hands = List.copyOf(hands);
    }
}
