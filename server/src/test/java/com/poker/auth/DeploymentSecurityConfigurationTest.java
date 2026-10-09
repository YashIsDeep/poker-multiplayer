package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentSecurityConfigurationTest {
    // Load the deployed YAML explicitly: src/test/resources/application.yml
    // otherwise shadows it and can conceal unsafe production defaults.
    private final ApplicationContextRunner mainConfiguration = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DeploymentSecurityConfiguration.class, LocalAuthConfiguration.class,
                    ModerationConfig.class, ModeratorPolicy.class)
            .withPropertyValues("spring.config.location=file:src/main/resources/", "POKER_MODERATOR_EMAILS=");

    @Test
    void actualProductionAndOauthConfigurationUsesSecureCookiesAndTwelveHourSessions() {
        for (String profiles : new String[] {"production", "prod", "oauth", "production,oauth"}) {
            mainConfiguration.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.secure", Boolean.class))
                        .isTrue();
                assertThat(context.getEnvironment().getProperty("spring.session.timeout")).isEqualTo("12h");
            });
        }
    }

    @Test
    void insecureCookiesFailOutsideStrictLoopbackDevelopment() {
        for (String profiles : new String[] {"production", "prod", "oauth", "production,oauth", "default"}) {
            mainConfiguration.withPropertyValues("spring.profiles.active=" + profiles,
                    "SESSION_COOKIE_SECURE=false", "server.address=127.0.0.1").run(context ->
                    assertThat(context).hasFailed());
        }
        for (String unsafe : new String[] {"server.address=0.0.0.0", "TUNNEL_MODE=true",
                "TUNNEL_PUBLIC_URL=https://example.com", "server.forward-headers-strategy=native"}) {
            mainConfiguration.withPropertyValues("spring.profiles.active=dev,oauth",
                    "SESSION_COOKIE_SECURE=false", unsafe).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void explicitLocalProfilesPreserveHttpDevelopmentIncludingOptedInLocalOauth() {
        for (String profiles : new String[] {"dev", "local-auth", "dev,oauth"}) {
            mainConfiguration.withPropertyValues("spring.profiles.active=" + profiles,
                    "SESSION_COOKIE_SECURE=false").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.secure", Boolean.class))
                        .isFalse();
            });
        }
    }

    @Test
    void unsetProductionAllowlistGrantsNobodyModeratorPrivileges() {
        mainConfiguration.withPropertyValues("spring.profiles.active=production").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModerationProperties.class).emails()).isEmpty();
            ModeratorPolicy policy = context.getBean(ModeratorPolicy.class);
            assertThat(policy.isModerator(google("yashparth.gupta@gmail.com"))).isFalse();
            assertThat(policy.isModerator(google("moderator@example.com"))).isFalse();
            assertThat(policy.isModerator(new SessionUserPrincipal(UUID.randomUUID(), AuthProvider.DEV,
                    "Guest", null))).isFalse();
        });
    }

    @Test
    void productionModeratorRequiresExplicitVerifiedProviderEmailAllowlist() {
        mainConfiguration.withPropertyValues("spring.profiles.active=production",
                "POKER_MODERATOR_EMAILS=moderator@example.com").run(context -> {
            assertThat(context).hasNotFailed();
            ModeratorPolicy policy = context.getBean(ModeratorPolicy.class);
            assertThat(policy.isModerator(google("moderator@example.com"))).isTrue();
            assertThat(policy.isModerator(google("another@example.com"))).isFalse();
        });
    }

    @Test
    void devModeratorOverrideFailsAtStartupOutsideStrictLocalDevelopment() {
        for (String profiles : new String[] {"production", "oauth", "default", "dev,production"}) {
            mainConfiguration.withPropertyValues("spring.profiles.active=" + profiles,
                    "poker.moderation.dev-users-are-moderators=true").run(context ->
                    assertThat(context).hasFailed());
        }
        mainConfiguration.withPropertyValues("spring.profiles.active=dev", "server.address=0.0.0.0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static SessionUserPrincipal google(String email) {
        return new SessionUserPrincipal(UUID.randomUUID(), AuthProvider.GOOGLE, "User", null, email);
    }
}
