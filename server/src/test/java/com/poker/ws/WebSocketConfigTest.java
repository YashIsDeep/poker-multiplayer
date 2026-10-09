package com.poker.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

class WebSocketConfigTest {
    @Test
    void preservesInboundOrderForSubscriptionLifecycleFrames() {
        PokerWebSocketProperties properties = new PokerWebSocketProperties();
        properties.setAllowedOrigins(List.of("https://poker.example"));
        WebSocketConfig config = new WebSocketConfig(
                properties,
                mock(MappingJackson2MessageConverter.class),
                mock(ActiveWebSocketSessionInterceptor.class),
                mock(WebSocketTransportRegistry.class),
                mock(TaskScheduler.class));
        StompEndpointRegistry endpoints = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(endpoints.addEndpoint("/ws")).thenReturn(endpoint);

        config.configureStompEndpoints(endpoints);

        verify(endpoints).setPreserveReceiveOrder(true);
        verify(endpoint).setAllowedOrigins("https://poker.example");
    }
}
