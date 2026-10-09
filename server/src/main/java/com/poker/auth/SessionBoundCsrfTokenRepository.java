package com.poker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Prevents CSRF validation from allocating a new persistent session on a
 * rejected request. Explicit bootstrap endpoints may still create a session,
 * while requests with an existing session may update that session.
 */
final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {
    private final CsrfTokenRepository delegate;
    private final RequestMatcher sessionCreatingRequestMatcher;

    SessionBoundCsrfTokenRepository(
            CsrfTokenRepository delegate,
            RequestMatcher sessionCreatingRequestMatcher) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sessionCreatingRequestMatcher = Objects.requireNonNull(
                sessionCreatingRequestMatcher, "sessionCreatingRequestMatcher");
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return new DeferredCsrfToken() {
            private CsrfToken token;
            private boolean initialized;
            private boolean generated;

            @Override
            public CsrfToken get() {
                initialize();
                return token;
            }

            @Override
            public boolean isGenerated() {
                initialize();
                return generated;
            }

            private void initialize() {
                if (initialized) {
                    return;
                }
                initialized = true;
                token = delegate.loadToken(request);
                if (token == null) {
                    generated = true;
                    token = delegate.generateToken(request);
                    saveToken(token, request, response);
                }
            }
        };
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (!sessionCreatingRequestMatcher.matches(request) && !hasExistingSession(request)) {
            return;
        }
        delegate.saveToken(token, request, response);
    }

    private static boolean hasExistingSession(HttpServletRequest request) {
        if (request.getRequestedSessionId() == null || !request.isRequestedSessionIdValid()) {
            return false;
        }

        HttpSession session = request.getSession(false);
        return session != null;
    }
}
