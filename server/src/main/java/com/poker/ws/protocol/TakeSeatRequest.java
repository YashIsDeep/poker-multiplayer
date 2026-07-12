package com.poker.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TakeSeatRequest(@NotNull UUID roomId, @Min(0) int seat) implements StrictProtocolObject {
}
