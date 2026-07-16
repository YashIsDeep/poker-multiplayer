package com.poker.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gate for the read-only live-state admin API. Disabled unless a profile
 * turns it on; the controller additionally refuses non-loopback callers.
 */
@ConfigurationProperties("poker.admin.live")
public record AdminLiveProperties(boolean enabled, String token) {
    public boolean tokenRequired() {
        return token != null && !token.isBlank();
    }
}
