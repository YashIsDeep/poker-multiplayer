package com.poker.room.application;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.RoomManager;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoomBeansConfig {
    @Bean
    RoomManager roomManager(Clock clock, JoinCodeGenerator joinCodeGenerator) {
        return new RoomManager(clock, joinCodeGenerator);
    }

    @Bean
    JoinCodeGenerator joinCodeGenerator() {
        return new JoinCodeGenerator();
    }
}
