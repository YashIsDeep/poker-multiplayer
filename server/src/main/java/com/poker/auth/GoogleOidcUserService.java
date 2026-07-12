package com.poker.auth;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserProvisioningService provisioningService;

    @Autowired
    public GoogleOidcUserService(UserProvisioningService provisioningService) {
        this(new OidcUserService(), provisioningService);
    }

    public GoogleOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserProvisioningService provisioningService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.provisioningService = Objects.requireNonNull(provisioningService, "provisioningService");
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        if (userRequest == null
                || !GOOGLE_REGISTRATION_ID.equals(
                        userRequest.getClientRegistration().getRegistrationId())) {
            throw OAuthMappingSupport.failure();
        }

        try {
            OidcUser providerUser = delegate.loadUser(userRequest);
            Map<String, Object> claims = providerUser.getClaims();
            Object rawSubject = claims.get("sub");
            if (!(rawSubject instanceof String subject) || subject.isBlank()) {
                throw OAuthMappingSupport.failure();
            }

            String name = OAuthMappingSupport.stringAttribute(claims, "name");
            String picture = OAuthMappingSupport.stringAttribute(claims, "picture");
            ExternalIdentity identity = new ExternalIdentity(
                    AuthProvider.GOOGLE,
                    subject,
                    DisplayNamePolicy.normalizeProviderName(name, subject),
                    picture);
            return new ProvisionedOidcUser(
                    providerUser,
                    SessionUserPrincipal.from(provisioningService.provision(identity)));
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw OAuthMappingSupport.failure();
        }
    }
}
