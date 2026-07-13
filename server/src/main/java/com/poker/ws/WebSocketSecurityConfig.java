package com.poker.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.config.annotation.web.messaging.MessageSecurityMetadataSourceRegistry;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {
    @Override
    protected void customizeClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new SubscriptionOwnershipInterceptor());
    }

    @Override
    protected void configureInbound(MessageSecurityMetadataSourceRegistry messages) {
        messages
                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                .simpTypeMatchers(
                        SimpMessageType.DISCONNECT,
                        SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.HEARTBEAT).authenticated()
                .simpMessageDestMatchers(
                        "/app/state.sync",
                        "/app/room.create",
                        "/app/room.join",
                        "/app/room.leave",
                        "/app/room.sync",
                        "/app/room.keep-alive",
                        "/app/room.take-seat",
                        "/app/room.stand",
                        "/app/game.act",
                        "/app/game.sit-out",
                        "/app/game.sit-in",
                        "/app/game.rebuy").authenticated()
                .simpSubscribeDestMatchers(
                        "/topic/lobby",
                        "/user/queue/acks",
                        "/user/queue/room",
                        "/user/queue/room-events").authenticated()
                .anyMessage().denyAll();
    }
}
