package com.poker.auth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

final class OAuthMappingSupport {
    private OAuthMappingSupport() {
    }

    static OAuth2AuthenticationException failure() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("oauth_login_failed"), "OAuth login failed");
    }

    static String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value instanceof String string ? string : null;
    }
}
