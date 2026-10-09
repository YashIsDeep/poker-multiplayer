package com.poker.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(AdminLiveProperties.class)
public class AdminLiveConfig {
    public AdminLiveConfig(AdminLiveProperties properties, Environment environment) {
        if (properties.enabled() && com.poker.auth.LoopbackRequestGuard.usesContainerForwarding(environment)) {
            throw new IllegalStateException(
                    "live admin API requires none/framework forwarding and no Tomcat RemoteIpValve header overrides");
        }
    }
}
