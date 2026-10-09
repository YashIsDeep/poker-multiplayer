package com.poker.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.config.annotation.web.messaging.MessageSecurityMetadataSourceRegistry;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {
    private final SubscriptionOwnershipInterceptor subscriptions;
    private final com.poker.hardening.InboundFrameBudgetInterceptor frameBudget;

    public WebSocketSecurityConfig(SubscriptionOwnershipInterceptor subscriptions,
            com.poker.hardening.InboundFrameBudgetInterceptor frameBudget) {
        this.subscriptions = subscriptions;
        this.frameBudget = frameBudget;
    }
    @Override
    protected void customizeClientInboundChannel(ChannelRegistration registration) {
        // Count every authenticated lifecycle frame before ownership validation
        // can discard unknown unsubscribe IDs.
        registration.interceptors(frameBudget, subscriptions);
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
                        "/app/ranked.join",
                        "/app/ranked.cancel",
                        "/app/room.create",
                        "/app/room.join",
                        "/app/room.leave",
                        "/app/room.sync",
                        "/app/room.keep-alive",
                        "/app/room.update-settings",
                        "/app/room.take-seat",
                        "/app/room.stand",
                        "/app/room.chat",
                        "/app/game.act",
                        "/app/game.sit-out",
                        "/app/game.sit-in",
                        "/app/game.rebuy").authenticated()
                .simpSubscribeDestMatchers(
                        "/topic/lobby",
                        "/user/queue/acks",
                        "/user/queue/room",
                        "/user/queue/room-events",
                        "/user/queue/chat",
                        "/user/queue/ranked").authenticated()
                .anyMessage().denyAll();
    }
}
