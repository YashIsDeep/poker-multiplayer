package com.poker.auth;

import com.poker.db.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"dev", "local-auth"})
public class DevLoginController {
    private final UserProvisioningService provisioningService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public DevLoginController(
            UserProvisioningService provisioningService,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.provisioningService = provisioningService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    @GetMapping("/auth/dev")
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
            String subject = DisplayNamePolicy.devSubject(displayName);
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
                    .location(URI.create("/lobby"))
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
