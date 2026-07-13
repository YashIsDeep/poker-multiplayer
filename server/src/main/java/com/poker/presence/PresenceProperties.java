package com.poker.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("poker.presence")
public class PresenceProperties {
    private Duration reconnectGrace = Duration.ofSeconds(10);

    public Duration getReconnectGrace() {
        return reconnectGrace;
    }

    public void setReconnectGrace(Duration value) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0
                || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("reconnect grace must be 1s-1m");
        }
        reconnectGrace = value;
    }
}
