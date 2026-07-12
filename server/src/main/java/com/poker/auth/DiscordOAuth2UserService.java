package com.poker.auth;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class DiscordOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final String DISCORD_REGISTRATION_ID = "discord";
    private static final String DISCORD_ID_PATTERN = "[0-9]{1,20}";
    private static final String DISCORD_AVATAR_HASH_PATTERN = "[A-Za-z0-9_-]{1,255}";

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final UserProvisioningService provisioningService;

    @Autowired
    public DiscordOAuth2UserService(UserProvisioningService provisioningService) {
        this(new DefaultOAuth2UserService(), provisioningService);
    }

    public DiscordOAuth2UserService(
            OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
            UserProvisioningService provisioningService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.provisioningService = Objects.requireNonNull(provisioningService, "provisioningService");
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        if (userRequest == null
                || !DISCORD_REGISTRATION_ID.equals(
                        userRequest.getClientRegistration().getRegistrationId())) {
            throw OAuthMappingSupport.failure();
        }

        try {
            OAuth2User providerUser = delegate.loadUser(userRequest);
            Map<String, Object> attributes = providerUser.getAttributes();
            String subject = OAuthMappingSupport.stringAttribute(attributes, "id");
            if (subject == null || !subject.matches(DISCORD_ID_PATTERN)) {
                throw OAuthMappingSupport.failure();
            }

            String globalName = OAuthMappingSupport.stringAttribute(attributes, "global_name");
            String username = OAuthMappingSupport.stringAttribute(attributes, "username");
            String displaySource = globalName;
            if (!DisplayNamePolicy.hasUsableProviderContent(displaySource)) {
                displaySource = username;
            }
            String avatarHash = OAuthMappingSupport.stringAttribute(attributes, "avatar");
            String avatar = avatarHash != null && avatarHash.matches(DISCORD_AVATAR_HASH_PATTERN)
                    ? "https://cdn.discordapp.com/avatars/" + subject + "/" + avatarHash + ".png"
                    : null;

            ExternalIdentity identity = new ExternalIdentity(
                    AuthProvider.DISCORD,
                    subject,
                    DisplayNamePolicy.normalizeProviderName(displaySource, subject),
                    avatar);
            return new ProvisionedOAuth2User(
                    providerUser,
                    SessionUserPrincipal.from(provisioningService.provision(identity)));
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (AuthenticationException ex) {
            throw OAuthMappingSupport.failure();
        } catch (RuntimeException ex) {
            throw OAuthMappingSupport.failure();
        }
    }
}
