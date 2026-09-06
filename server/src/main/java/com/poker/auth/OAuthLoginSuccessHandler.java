package com.poker.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

public final class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public OAuthLoginSuccessHandler(
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.securityContextRepository = Objects.requireNonNull(securityContextRepository);
        this.sessionAuthenticationStrategy = Objects.requireNonNull(sessionAuthenticationStrategy);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        SessionUserPrincipal principal = applicationPrincipal(authentication.getPrincipal());
        if (principal == null) {
            // The OAuth filter has already saved the protocol authentication into the
            // session; overwrite it with an empty context so no provider principal,
            // claims, or tokens remain persisted.
            SecurityContextHolder.clearContext();
            securityContextRepository.saveContext(
                    SecurityContextHolder.createEmptyContext(), request, response);
            redirectStrategy.sendRedirect(request, response, "/login?loginError");
            return;
        }

        Authentication applicationAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities());
        sessionAuthenticationStrategy.onAuthentication(applicationAuthentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(applicationAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        redirectStrategy.sendRedirect(request, response, "/");
    }

    private static SessionUserPrincipal applicationPrincipal(Object principal) {
        if (principal instanceof ProvisionedOidcUser oidcUser) {
            return oidcUser.applicationPrincipal();
        }
        if (principal instanceof ProvisionedOAuth2User oauthUser) {
            return oauthUser.applicationPrincipal();
        }
        return null;
    }
}
