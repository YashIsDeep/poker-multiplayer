package com.poker.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.AssertTrue;

@Validated
@ConfigurationProperties("poker.rooms")
public class RoomIdleProperties {
    private Duration disconnectedGrace = Duration.ofMinutes(10);
    private Duration memberDisconnectedGrace = Duration.ofMinutes(10);
    private Duration inactivityTimeout = Duration.ofMinutes(60);
    private Duration inactivityWarning = Duration.ofMinutes(5);
    private long cleanupSweepMs = 60_000;

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

    public Duration getMemberDisconnectedGrace() {
        return memberDisconnectedGrace;
    }

    public void setMemberDisconnectedGrace(Duration value) {
        memberDisconnectedGrace = cleanupDuration(value, "member disconnected grace");
    }

    public Duration getInactivityTimeout() {
        return inactivityTimeout;
    }

    public void setInactivityTimeout(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0
                || value.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("inactivity timeout must be 1m-24h");
        }
        inactivityTimeout = value;
    }

    public Duration getInactivityWarning() {
        return inactivityWarning;
    }

    public void setInactivityWarning(Duration value) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("inactivity warning must be >0 and <=1h");
        }
        inactivityWarning = value;
    }

    public long getCleanupSweepMs() {
        return cleanupSweepMs;
    }

    public void setCleanupSweepMs(long value) {
        if (value < 1_000 || value > 3_600_000) {
            throw new IllegalArgumentException("cleanup sweep must be 1s-1h");
        }
        cleanupSweepMs = value;
    }

    private static Duration cleanupDuration(Duration value, String name) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0
                || value.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(name + " must be 1m-24h");
        }
        return value;
    }

    @AssertTrue(message = "inactivity warning must be shorter than inactivity timeout")
    public boolean isInactivityWarningBeforeTimeout() {
        return inactivityWarning.compareTo(inactivityTimeout) < 0;
    }
}
