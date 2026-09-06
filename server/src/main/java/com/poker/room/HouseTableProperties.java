package com.poker.room;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The one public table seeded at startup so the lobby is never empty. */
@ConfigurationProperties("poker.house-table")
public record HouseTableProperties(boolean enabled, String name) {
    public HouseTableProperties {
        name = name == null || name.isBlank() ? "House Table" : name;
    }
}
