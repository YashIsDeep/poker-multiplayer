package com.poker.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HandArchiveProperties.class)
public class HandArchiveConfig {
}
