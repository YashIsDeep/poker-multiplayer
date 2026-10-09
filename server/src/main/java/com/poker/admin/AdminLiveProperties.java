package com.poker.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gate for the read-only live-state admin API. Disabled unless a profile
 * turns it on; every enabled instance requires a token, including development.
 */
@ConfigurationProperties("poker.admin.live")
public record AdminLiveProperties(boolean enabled, String token) {
    public AdminLiveProperties {
        if (enabled && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("poker.admin.live.token must be set whenever the live admin API is enabled");
        }
    }

    @Override
    public String toString() {
        return "AdminLiveProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
