package com.poker.db.user;

import com.poker.auth.AuthProvider;
import com.poker.auth.ExternalIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "app_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_app_user_provider_subject",
                columnNames = {"provider", "provider_subject"}))
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected UserAccount() {
    }

    public UserAccount(ExternalIdentity identity, Instant now) {
        this.provider = identity.provider();
        this.providerSubject = identity.providerSubject();
        this.displayName = identity.displayName();
        this.avatarUrl = identity.avatarUrl();
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public void refreshFrom(ExternalIdentity identity, Instant now) {
        this.displayName = identity.displayName();
        this.avatarUrl = identity.avatarUrl();
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
