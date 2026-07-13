package com.poker.hardening;

import com.poker.presence.RoomIdleProperties;
import com.poker.presence.PresenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({HardeningProperties.class, RoomIdleProperties.class, PresenceProperties.class})
public class HardeningConfig {}
