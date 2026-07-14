package com.poker.ws.protocol;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record UpdateRoomSettingsRequest(
        @NotNull UUID roomId,
        @Positive long bigBlind,
        @Positive long startingStack,
        @Min(10) @Max(120) int actionTimeoutSeconds,
        boolean rebuysEnabled,
        @Positive long rebuyStack,
        @Min(0) @Max(20) int rebuyDelayHands) implements StrictProtocolObject {
    public UpdateRoomSettingsRequest {
        // Older clients omitted this field; retain the established 30-second default.
        if (actionTimeoutSeconds == 0) actionTimeoutSeconds = 30;
    }

    /** Backwards-compatible constructor for callers that use the original settings payload. */
    public UpdateRoomSettingsRequest(
            UUID roomId, long bigBlind, long startingStack, boolean rebuysEnabled,
            long rebuyStack, int rebuyDelayHands) {
        this(roomId, bigBlind, startingStack, 30, rebuysEnabled, rebuyStack, rebuyDelayHands);
    }
}
