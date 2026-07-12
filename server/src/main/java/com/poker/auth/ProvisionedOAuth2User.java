package com.poker.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class ProvisionedOAuth2User implements OAuth2User {
    private final OAuth2User delegate;
    private final SessionUserPrincipal applicationPrincipal;

    public ProvisionedOAuth2User(OAuth2User delegate, SessionUserPrincipal applicationPrincipal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.applicationPrincipal = Objects.requireNonNull(applicationPrincipal, "applicationPrincipal");
    }

    public SessionUserPrincipal applicationPrincipal() {
        return applicationPrincipal;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return applicationPrincipal.authorities();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public String getName() {
        return applicationPrincipal.getName();
    }
}
