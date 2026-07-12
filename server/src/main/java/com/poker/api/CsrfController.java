package com.poker.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Materializes the session CSRF token for the browser STOMP client. */
@RestController
public class CsrfController {
    private final CsrfTokenRepository tokenRepository;

    public CsrfController(CsrfTokenRepository tokenRepository) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository, "tokenRepository");
    }

    @GetMapping("/api/csrf")
    public ResponseEntity<CsrfResponse> csrf(
            HttpServletRequest request,
            @AuthenticationPrincipal Object ignoredPrincipal) {
        CsrfToken token = tokenRepository.loadToken(request);
        if (token == null) {
            token = tokenRepository.generateToken(request);
            tokenRepository.saveToken(token, request, null);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }
}
