package com.poker.auth;

import com.poker.hardening.BoundedTokenBucketRegistry;
import com.poker.hardening.HardeningProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds anonymous OAuth authorization-state sessions per transport peer. */
final class OAuthInitiationRateLimitFilter extends OncePerRequestFilter {
    private static final String AUTHORIZATION_BASE_PATH = "/oauth2/authorization";

    private final BoundedTokenBucketRegistry<String> peers;

    OAuthInitiationRateLimitFilter(HardeningProperties properties) {
        this(properties, System::nanoTime);
    }

    OAuthInitiationRateLimitFilter(HardeningProperties properties, LongSupplier ticker) {
        Objects.requireNonNull(properties, "properties");
        peers = new BoundedTokenBucketRegistry<>(
                properties.getOauthInitiationRegistryCap(),
                properties.getOauthInitiationRegistryTtl(),
                properties.getOauthInitiationRate(),
                properties.getOauthInitiationBurst(),
                Objects.requireNonNull(ticker, "ticker"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAuthorizationInitiation(request)) {
            chain.doFilter(request, response);
            return;
        }

        String peer = request.getRemoteAddr();
        var admission = peers.admit(peer == null || peer.isBlank() ? "unknown" : peer);
        if (!admission.accepted()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString((admission.retryAfterMs() + 999) / 1_000));
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isAuthorizationInitiation(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (path == null) {
            path = request.getServletPath();
        } else if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path == null) {
            return false;
        }
        return AUTHORIZATION_BASE_PATH.equals(path)
                || path.startsWith(AUTHORIZATION_BASE_PATH + "/");
    }
}
