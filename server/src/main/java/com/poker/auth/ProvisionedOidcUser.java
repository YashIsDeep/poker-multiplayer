package com.poker.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class ProvisionedOidcUser implements OidcUser {
    private final OidcUser delegate;
    private final SessionUserPrincipal applicationPrincipal;

    public ProvisionedOidcUser(OidcUser delegate, SessionUserPrincipal applicationPrincipal) {
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
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public String getName() {
        return applicationPrincipal.getName();
    }
}
