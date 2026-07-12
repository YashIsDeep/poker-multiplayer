package com.poker.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record TakeSeatRequest(@NotNull UUID roomId, @Min(0) int seat, @Positive long buyIn) implements StrictProtocolObject {
    public TakeSeatRequest(UUID roomId, int seat) { this(roomId, seat, 0); }
}
