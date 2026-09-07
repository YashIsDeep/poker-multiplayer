package com.poker.room.application;

import com.poker.room.HandHistory;
import java.util.UUID;

/** Durable, idempotent completion boundary. A failure must stop ranked progression. */
@FunctionalInterface
public interface RankedSettlement {
    void settle(UUID roomId, HandHistory hand);
}
