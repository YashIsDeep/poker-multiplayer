package com.poker.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.poker.hardening.HardeningProperties;
import com.poker.ws.WebSocketTransportRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserSocketRegistryTest {
    @Test
    void userLimitCannotBeBypassedByRotatingHttpSessions() {
        UserSocketRegistry registry = registry(2, 3, 64);
        UUID user = UUID.randomUUID();
        registry.register(user, "a", "http-a");
        registry.register(user, "b", "http-b");
        for (int i = 0; i < 5_000; i++) {
            String suffix = Integer.toString(i);
            assertThatThrownBy(() -> registry.register(user, "excess-" + suffix, "http-" + suffix))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(registry.total()).isEqualTo(2);
        assertThat(registry.trackedHttpSessionCount()).isEqualTo(2);
        registry.remove("a");
        registry.register(user, "replacement", "http-replacement");
        assertThat(registry.count(user)).isEqualTo(2);
    }

    @Test
    void httpSessionLimitCannotBeBypassedByChangingUsers() {
        UserSocketRegistry registry = registry(3, 2, 64);
        registry.register(UUID.randomUUID(), "a", "http");
        registry.register(UUID.randomUUID(), "b", "http");
        for (int i = 0; i < 5_000; i++) {
            String socket = "excess-" + i;
            assertThatThrownBy(() -> registry.register(UUID.randomUUID(), socket, "http"))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(registry.total()).isEqualTo(2);
        assertThat(registry.socketCountsByUser()).hasSize(2);
        assertThat(registry.removeHttpSession("http")).hasSize(2);
        assertThat(registry.total()).isZero();
        assertThat(registry.socketCountsByUser()).isEmpty();
        assertThat(registry.trackedHttpSessionCount()).isZero();
        registry.register(UUID.randomUUID(), "replacement", "http");
        assertThat(registry.total()).isOne();
    }

    @Test
    void globalLimitAndCleanupBoundThousandsOfDistinctIdentities() {
        UserSocketRegistry registry = registry(2, 2, 32);
        for (int i = 0; i < 32; i++) registry.register(UUID.randomUUID(), "s-" + i, "h-" + i);
        for (int i = 32; i < 5_000; i++) {
            String suffix = Integer.toString(i);
            assertThatThrownBy(() -> registry.register(UUID.randomUUID(), "s-" + suffix, "h-" + suffix))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(registry.total()).isEqualTo(32);
        assertThat(registry.socketCountsByUser()).hasSize(32);
        assertThat(registry.trackedHttpSessionCount()).isEqualTo(32);
        for (int i = 0; i < 32; i++) registry.remove("s-" + i);
        for (int i = 0; i < 5_000; i++) {
            registry.register(UUID.randomUUID(), "churn-" + i, "churn-http-" + i);
            registry.remove("churn-" + i);
        }
        assertThat(registry.total()).isZero();
        assertThat(registry.socketCountsByUser()).isEmpty();
        assertThat(registry.trackedHttpSessionCount()).isZero();
    }

    @Test
    void transportCloseCleansPresenceOnceEvenBeforeStompDisconnect() {
        UserSocketRegistry registry = new UserSocketRegistry();
        RoomIdleCoordinator idle = mock(RoomIdleCoordinator.class);
        StompPresenceListener listener = new StompPresenceListener(registry, idle);
        UUID user = UUID.randomUUID();
        registry.register(user, "a", "http");
        registry.register(user, "b", "http");
        listener.closed(new WebSocketTransportRegistry.SocketClosed("a"));
        verifyNoInteractions(idle);
        listener.closed(new WebSocketTransportRegistry.SocketClosed("b"));
        listener.closed(new WebSocketTransportRegistry.SocketClosed("b"));
        verify(idle).disconnected(user);
        assertThat(registry.total()).isZero();
    }

    private static UserSocketRegistry registry(int user, int http, int total) {
        HardeningProperties properties = new HardeningProperties();
        properties.setMaxSocketsPerUser(user);
        properties.setMaxSocketsPerHttpSession(http);
        properties.setRegistryCap(total);
        return new UserSocketRegistry(properties);
    }
}
