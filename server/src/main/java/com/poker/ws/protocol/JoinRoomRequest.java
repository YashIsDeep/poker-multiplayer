package com.poker.ws.protocol;

import jakarta.validation.constraints.NotBlank;

public record JoinRoomRequest(@NotBlank String joinCode) implements StrictProtocolObject {
}
