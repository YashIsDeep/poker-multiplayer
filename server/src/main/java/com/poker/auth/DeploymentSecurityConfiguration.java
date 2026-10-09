package com.poker.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Fails before serving requests when development exceptions escape loopback. */
@Configuration(proxyBeanMethods = false)
public class DeploymentSecurityConfiguration {
    public DeploymentSecurityConfiguration(Environment environment) {
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
            try {
                LocalAuthConfiguration.requireStrictlyLocalDevelopment(environment);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("Insecure session cookies require strictly local development; "
                        + "public and OAuth deployments must use Secure cookies and HTTPS.", exception);
            }
        }
        if (environment.getProperty("poker.moderation.dev-users-are-moderators", Boolean.class, false)) {
            try {
                LocalAuthConfiguration.requireStrictlyLocalDevelopment(environment);
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("Guest moderator privileges require strictly local development; "
                        + "configure POKER_MODERATOR_EMAILS explicitly for deployed moderators.", exception);
            }
        }
    }
}
