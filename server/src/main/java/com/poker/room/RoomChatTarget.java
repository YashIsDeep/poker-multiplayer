package com.poker.room;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Membership and sender data captured atomically for one transient chat send. */
public record RoomChatTarget(UUID roomId, MemberState sender, Set<UUID> recipients) {
    public RoomChatTarget {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipients, "recipients");
        if (!recipients.contains(sender.userId())) {
            throw new IllegalArgumentException("chat sender must be a recipient");
        }
        recipients = Set.copyOf(recipients);
    }
}
