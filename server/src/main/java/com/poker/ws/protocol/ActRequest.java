package com.poker.ws.protocol;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record ActRequest(@NotNull UUID roomId, @PositiveOrZero long actionSeq,
                         @NotNull String action, Long amount) implements StrictProtocolObject {}
