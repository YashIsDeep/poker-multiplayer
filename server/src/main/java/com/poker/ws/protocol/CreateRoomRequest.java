package com.poker.ws.protocol;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateRoomRequest(@NotBlank String name, @Min(2) @Max(9) int maxSeats)
        implements StrictProtocolObject {
}
