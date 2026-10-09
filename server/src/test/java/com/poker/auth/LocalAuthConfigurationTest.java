package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalAuthConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalAuthConfiguration.class)
            .withPropertyValues("spring.profiles.active=local-auth", "server.address=127.0.0.1");

    @Test
    void explicitlyLoopbackGuestModeStarts() {
        context.run(application -> assertThat(application).hasNotFailed());
    }

    @Test
    void publicAndUnsetListenersFailStartup() {
        for (String address : new String[] {"", "0.0.0.0", "::", "192.168.1.10", "public.example"}) {
            context.withPropertyValues("server.address=" + address).run(application ->
                    assertThat(application).hasFailed());
        }
    }

    @Test
    void productionGuestProfilesAndTunnelsFailStartup() {
        for (String property : new String[] {"spring.profiles.active=local-auth,production",
                "spring.profiles.active=dev,prod", "TUNNEL_MODE=true", "PROXY_MODE=production",
                "VITE_TUNNEL_HOST=table.example", "TUNNEL_PUBLIC_URL=https://table.example",
                "server.forward-headers-strategy=native", "server.tomcat.remoteip.remote-ip-header=X-Client-IP",
                "server.tomcat.remoteip.protocol-header=X-Client-Proto",
                "server.tomcat.remoteip.remoteIpHeader=X-Client-IP"}) {
            context.withPropertyValues(property).run(application -> assertThat(application).hasFailed());
        }
    }

    @Test
    void oauthOnlyProfileIsUnaffected() {
        context.withPropertyValues("spring.profiles.active=oauth", "server.address=0.0.0.0")
                .run(application -> assertThat(application).hasNotFailed());
    }
}
