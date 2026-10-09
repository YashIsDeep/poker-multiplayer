package com.poker.auth;

import com.poker.db.user.UserAccount;
import com.poker.api.CsrfResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"dev", "local-auth"})
public class DevLoginController {
    private final UserProvisioningService provisioningService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;

    public DevLoginController(
            UserProvisioningService provisioningService,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository) {
        this.provisioningService = provisioningService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/auth/dev/csrf")
    public ResponseEntity<CsrfResponse> csrf(
            HttpServletRequest request,
            HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(token, request, response);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/auth/dev")
    public ResponseEntity<Void> login(
            @RequestParam(name = "u", required = false) String rawName,
            HttpServletRequest request,
            HttpServletResponse response) {
        final String displayName;
        try {
            displayName = DisplayNamePolicy.normalizeDevName(rawName);
        } catch (IllegalArgumentException ex) {
            return noStore(HttpStatus.BAD_REQUEST);
        }

        try {
            // A name is presentation data, never proof of ownership of an account.
            String subject = UUID.randomUUID().toString();
            UserAccount account = provisioningService.provision(
                    new ExternalIdentity(AuthProvider.DEV, subject, displayName, null));
            SessionUserPrincipal principal = SessionUserPrincipal.from(account);
            Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.authorities());
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .cacheControl(CacheControl.noStore())
                    .location(URI.create("/"))
                    .build();
        } catch (RuntimeException ex) {
            SecurityContextHolder.clearContext();
            return noStore(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static ResponseEntity<Void> noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
