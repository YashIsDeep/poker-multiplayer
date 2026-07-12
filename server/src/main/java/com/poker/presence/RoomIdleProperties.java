package com.poker.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poker.rooms")
public class RoomIdleProperties {
    private Duration disconnectedGrace = Duration.ofMinutes(30);

    public Duration getDisconnectedGrace() {
        return disconnectedGrace;
    }

    public void setDisconnectedGrace(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0
                || value.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("disconnected grace must be 1m-24h");
        }
        disconnectedGrace = value;
    }
}
