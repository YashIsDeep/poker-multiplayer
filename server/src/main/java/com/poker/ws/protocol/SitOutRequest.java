package com.poker.ws.protocol;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record SitOutRequest(@NotNull UUID roomId) implements StrictProtocolObject {}
