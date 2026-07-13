package com.poker.api;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {
    private final Environment environment;

    public MetaController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/api/meta")
    public Map<String, String> meta() {
        return Map.of(
                "title", "HOLD'EM",
                "tagline", "multiplayer texas hold'em",
                "authMode", authMode());
    }

    private String authMode() {
        boolean oauth = environment.acceptsProfiles(Profiles.of("oauth"));
        boolean dev = environment.acceptsProfiles(Profiles.of("dev", "local-auth"));
        if (oauth && dev) {
            return "both";
        }
        if (oauth) {
            return "oauth";
        }
        if (dev) {
            return "dev";
        }
        return "none";
    }
}
