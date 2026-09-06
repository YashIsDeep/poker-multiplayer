package com.poker.room.application;

import com.poker.room.HouseTableProperties;
import com.poker.room.RoomConfig;
import com.poker.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the permanent public table once the context is up. Rooms are in-memory,
 * so this runs on every boot; a failure here must not take the server down.
 */
@Component
public class HouseTableBootstrap {
    private static final Logger log = LoggerFactory.getLogger(HouseTableBootstrap.class);

    private final RoomManager rooms;
    private final HouseTableProperties properties;

    public HouseTableBootstrap(RoomManager rooms, HouseTableProperties properties) {
        this.rooms = rooms;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!properties.enabled()) {
            return;
        }
        try {
            var change = rooms.createHouseTable(
                    properties.name(), RoomConfig.PUBLIC_SEATS, RoomConfig.PUBLIC_DEFAULT);
            log.info("Seeded house table roomId={} name={}", change.roomId(), properties.name());
        } catch (RuntimeException ex) {
            log.error("House table seeding failed; the lobby starts empty", ex);
        }
    }
}
