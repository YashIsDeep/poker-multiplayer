package com.poker.ws;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.session.Session;
import org.springframework.session.web.socket.config.annotation.AbstractSessionWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;

@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(PokerWebSocketProperties.class)
public class WebSocketConfig extends AbstractSessionWebSocketMessageBrokerConfigurer<Session> {
    private final PokerWebSocketProperties properties;
    private final MappingJackson2MessageConverter stompMessageConverter;
    private final ActiveWebSocketSessionInterceptor activeSessionInterceptor;
    private final org.springframework.scheduling.TaskScheduler brokerTaskScheduler;
    private final WebSocketTransportRegistry transports;

    public WebSocketConfig(
            PokerWebSocketProperties properties,
            MappingJackson2MessageConverter stompMessageConverter,
            ActiveWebSocketSessionInterceptor activeSessionInterceptor,
            WebSocketTransportRegistry transports,
            @org.springframework.beans.factory.annotation.Qualifier("gameplayTaskScheduler")
            org.springframework.scheduling.TaskScheduler brokerTaskScheduler) {
        this.properties = properties;
        this.stompMessageConverter = stompMessageConverter;
        this.activeSessionInterceptor = activeSessionInterceptor;
        this.transports = transports;
        this.brokerTaskScheduler = brokerTaskScheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        super.configureClientInboundChannel(registration);
        registration.interceptors(activeSessionInterceptor);
    }

    @Override
    protected void configureStompEndpoints(StompEndpointRegistry registry) {
        // Subscription ownership is updated on the inbound channel. Preserve
        // per-socket order so the broker observes SUBSCRIBE/UNSUBSCRIBE in the
        // same order as those quota checks.
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws")
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeatMillis = properties.getHeartbeatInterval().toMillis();
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {heartbeatMillis, heartbeatMillis})
                .setTaskScheduler(brokerTaskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> converters) {
        converters.add(stompMessageConverter);
        // Do not append Spring's default Jackson converter: it uses the
        // application mapper and would silently bypass the strict wire policy.
        return false;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        super.configureWebSocketTransport(registration);
        registration
                .addDecoratorFactory(transports::decorate)
                .setMessageSizeLimit(properties.getMaxMessageSize())
                .setSendBufferSizeLimit(properties.getSendBufferSize())
                .setSendTimeLimit(properties.getSendTimeLimit())
                .setTimeToFirstMessage(properties.getTimeToFirstMessage());
    }
}
