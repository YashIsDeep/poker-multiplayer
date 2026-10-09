package com.poker.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AdminLiveConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdminLiveConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"dev", "local-auth", "oauth", "prod"})
    void enabledApiCannotStartWithoutTokenInAnyProfile(String profile) {
        contextRunner.withPropertyValues("poker.admin.live.enabled=true", "spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "poker.admin.live.token must be set whenever the live admin API is enabled");
                });
    }

    @Test
    void enabledApiCannotStartWithBlankToken() {
        contextRunner.withPropertyValues("poker.admin.live.enabled=true", "poker.admin.live.token=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledApiNeedsNoToken() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdminLiveProperties.class).enabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "framework"})
    void tokenAndInspectableTransportAllowStartup(String forwardingStrategy) {
        contextRunner.withPropertyValues("poker.admin.live.enabled=true", "poker.admin.live.token=test-admin-token",
                        "server.forward-headers-strategy=" + forwardingStrategy)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.forward-headers-strategy=native",
            "server.tomcat.remoteip.remote-ip-header=X-Client-IP",
            "server.tomcat.remoteip.protocol-header=X-Client-Proto",
            "server.tomcat.remoteip.remoteIpHeader=X-Client-IP"})
    void containerAddressRewritingCannotBeEnabledWithLiveAdmin(String override) {
        contextRunner.withPropertyValues("poker.admin.live.enabled=true", "poker.admin.live.token=test-admin-token",
                        "server.forward-headers-strategy=framework", override)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "live admin API requires none/framework forwarding and no Tomcat RemoteIpValve header overrides");
                });
    }
}
