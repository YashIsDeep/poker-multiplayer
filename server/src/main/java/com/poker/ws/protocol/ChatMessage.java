package com.poker.ws.protocol;

import java.time.Instant;
import java.util.UUID;

/** A live-only room chat event; it is deliberately absent from room snapshots. */
public record ChatMessage(
        UUID id,
        UUID roomId,
        UUID senderId,
        String senderName,
        String message,
        Instant sentAt) implements StrictProtocolObject {
}
