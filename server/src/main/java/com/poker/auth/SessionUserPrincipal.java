package com.poker.auth;

import com.poker.db.user.UserAccount;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class SessionUserPrincipal implements CurrentUserPrincipal {
    private static final long serialVersionUID = 1L;
    private static final List<GrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_USER"));

    private final UUID userId;
    private final AuthProvider provider;
    private final String displayName;
    private final String avatarUrl;

    public SessionUserPrincipal(UUID userId, AuthProvider provider, String displayName, String avatarUrl) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.avatarUrl = avatarUrl;
    }

    public static SessionUserPrincipal from(UserAccount account) {
        return new SessionUserPrincipal(
                account.getId(),
                account.getProvider(),
                account.getDisplayName(),
                account.getAvatarUrl());
    }

    public List<GrantedAuthority> authorities() {
        return AUTHORITIES;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public AuthProvider provider() {
        return provider;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String avatarUrl() {
        return avatarUrl;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionUserPrincipal that)) {
            return false;
        }
        return userId.equals(that.userId)
                && provider == that.provider
                && displayName.equals(that.displayName)
                && Objects.equals(avatarUrl, that.avatarUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, provider, displayName, avatarUrl);
    }

    @Override
    public String toString() {
        return "SessionUserPrincipal{userId=" + userId + ", provider=" + provider
                + ", displayName=" + displayName + "}";
    }
}
