package com.poker.ws.protocol;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
public record RebuyRequest(@NotNull UUID roomId, @Positive long amount) implements StrictProtocolObject {}
