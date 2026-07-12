package com.poker.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;

@Configuration
public class StompMessageConfig {
    @Bean
    MappingJackson2MessageConverter stompMessageConverter(ObjectMapper applicationMapper) {
        ObjectMapper strict = applicationMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter(strict);
        converter.setStrictContentTypeMatch(false);
        return converter;
    }
}
