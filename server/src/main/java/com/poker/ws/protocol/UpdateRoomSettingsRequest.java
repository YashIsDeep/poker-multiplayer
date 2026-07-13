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
        boolean rebuysEnabled,
        @Positive long rebuyStack,
        @Min(0) @Max(20) int rebuyDelayHands) implements StrictProtocolObject {}
