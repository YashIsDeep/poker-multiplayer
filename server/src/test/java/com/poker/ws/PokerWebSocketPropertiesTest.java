package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PokerWebSocketPropertiesTest {
    @Test
    void defaultsToSymmetricFiveSecondHeartbeatInterval() {
        assertThat(new PokerWebSocketProperties().getHeartbeatInterval())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsHeartbeatIntervalsOutsideSafeBounds() {
        PokerWebSocketProperties properties = new PokerWebSocketProperties();
        assertThatThrownBy(() -> properties.setHeartbeatInterval(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setHeartbeatInterval(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
