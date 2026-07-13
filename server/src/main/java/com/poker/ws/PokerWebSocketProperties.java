package com.poker.ws;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "poker.websocket")
public class PokerWebSocketProperties {
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:5173",
            "http://localhost:5173"));

    @Min(1024)
    @Max(1024 * 1024)
    private int maxMessageSize = 16 * 1024;

    @Min(16 * 1024)
    @Max(4 * 1024 * 1024)
    private int sendBufferSize = 256 * 1024;

    @Min(1000)
    @Max(120_000)
    private int sendTimeLimit = 10_000;

    @Min(1000)
    @Max(120_000)
    private int timeToFirstMessage = 10_000;

    private Duration heartbeatInterval = Duration.ofSeconds(5);

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(origin ->
                origin == null || origin.isBlank() || "*".equals(origin.trim()))) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        this.allowedOrigins = new ArrayList<>(allowedOrigins);
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }

    public int getSendTimeLimit() {
        return sendTimeLimit;
    }

    public void setSendTimeLimit(int sendTimeLimit) {
        this.sendTimeLimit = sendTimeLimit;
    }

    public int getTimeToFirstMessage() {
        return timeToFirstMessage;
    }

    public void setTimeToFirstMessage(int timeToFirstMessage) {
        this.timeToFirstMessage = timeToFirstMessage;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        if (heartbeatInterval == null
                || heartbeatInterval.compareTo(Duration.ofSeconds(1)) < 0
                || heartbeatInterval.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("heartbeat interval must be 1s-60s");
        }
        this.heartbeatInterval = heartbeatInterval;
    }
}
