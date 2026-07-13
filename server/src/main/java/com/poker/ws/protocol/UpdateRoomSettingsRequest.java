package com.poker.ws.protocol;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record UpdateRoomSettingsRequest(
        @NotNull UUID roomId,
        @Positive long bigBlind,
        @Positive long startingStack,
        boolean rebuysEnabled,
        @Positive long rebuyStack) implements StrictProtocolObject {}
