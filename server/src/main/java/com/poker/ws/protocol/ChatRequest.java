package com.poker.ws.protocol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatRequest(
        @NotNull UUID roomId,
        @NotBlank @Size(max = 50) String message) implements StrictProtocolObject {
    public ChatRequest {
        if (message != null) {
            message = message.strip();
        }
    }
}
