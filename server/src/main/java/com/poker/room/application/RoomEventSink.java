package com.poker.room.application;

import com.poker.room.RoomChange;

@FunctionalInterface
public interface RoomEventSink {
    PublicationReport publish(RoomChange change);
}
