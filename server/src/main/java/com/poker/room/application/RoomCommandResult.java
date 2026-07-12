package com.poker.room.application;

import com.poker.room.RoomChange;
import java.util.Objects;

public record RoomCommandResult(RoomChange change, PublicationReport publication) {
    public RoomCommandResult {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(publication, "publication");
    }
}
