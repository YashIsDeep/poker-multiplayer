package com.poker.ws.protocol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CommandEnvelope<T>(
        @NotBlank @Size(max = 36) String requestId,
        @NotNull @Valid T payload) implements StrictProtocolObject {
    public CommandEnvelope {
        if (requestId != null) {
            try {
                UUID parsed = UUID.fromString(requestId);
                if (!parsed.toString().equals(requestId)) {
                    throw new IllegalArgumentException("requestId must be canonical");
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("requestId must be a canonical UUID", ex);
            }
        }
    }
}
