package com.poker.room.application;

import com.poker.room.JoinCodeGenerator;
import com.poker.room.RoomManager;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.poker.engine.cards.Deck;
import java.security.SecureRandom;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.TaskScheduler;
import com.poker.presence.UserSocketRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        com.poker.room.HouseTableProperties.class)
public class RoomBeansConfig {
    @Bean
    RoomManager roomManager(Clock clock, JoinCodeGenerator joinCodeGenerator, DeckFactory deckFactory) {
        return new RoomManager(clock, joinCodeGenerator, deckFactory);
    }

    @Bean
    JoinCodeGenerator joinCodeGenerator() {
        return new JoinCodeGenerator();
    }

    @Bean DeckFactory deckFactory() { SecureRandom random = new SecureRandom(); return () -> Deck.shuffled(random); }

    @Bean(name = {"gameplayTaskScheduler", "taskScheduler"})
    TaskScheduler gameplayTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("poker-game-"); scheduler.initialize(); return scheduler;
    }

    @Bean UserSocketRegistry userSocketRegistry(com.poker.hardening.HardeningProperties properties) {
        return new UserSocketRegistry(properties);
    }
}
