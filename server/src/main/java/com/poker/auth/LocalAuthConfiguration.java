package com.poker.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Guest authentication is available only on an explicitly local listener. */
@Configuration(proxyBeanMethods = false)
@Profile({"dev", "local-auth"})
public class LocalAuthConfiguration {
    public LocalAuthConfiguration(Environment environment) {
        requireStrictlyLocalDevelopment(environment);
    }

    /** Shared gate for development-only authentication, cookie and privilege exceptions. */
    static void requireStrictlyLocalDevelopment(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "local-auth"))) {
            throw new IllegalStateException("A development exception requires the dev or local-auth profile.");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Guest authentication cannot be enabled in production profiles.");
        }
        String address = environment.getProperty("server.address", "");
        if (!LoopbackRequestGuard.isLoopbackAddress(address)) {
            throw new IllegalStateException("Guest authentication requires server.address to be a numeric loopback address.");
        }
        if (LoopbackRequestGuard.usesContainerForwarding(environment)) {
            throw new IllegalStateException("Guest authentication does not support native forwarded-header processing.");
        }
        if (enabled(environment.getProperty("TUNNEL_MODE"))
                || hasText(environment.getProperty("PROXY_MODE"))
                || hasText(environment.getProperty("VITE_TUNNEL_HOST"))
                || hasText(environment.getProperty("TUNNEL_PUBLIC_URL"))) {
            throw new IllegalStateException("Guest authentication cannot be exposed through a tunnel; use OAuth.");
        }
    }

    private static boolean enabled(String value) {
        return hasText(value) && !java.util.Set.of("false", "0", "off", "no")
                .contains(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
