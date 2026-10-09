package com.poker.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class LoopbackRequestGuardConfig {
    @Bean
    LoopbackRequestGuard loopbackRequestGuard(Environment environment) {
        return new LoopbackRequestGuard(environment.acceptsProfiles(Profiles.of("dev", "local-auth")));
    }
}
