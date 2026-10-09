package com.poker.auth;

import com.poker.hardening.HardeningProperties;
import com.poker.ws.WebSocketSessionLogoutHandler;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final RequestMatcher API_REQUEST = request -> matchesNamespace(request, "/api");
    private static final RequestMatcher WEBSOCKET_REQUEST = request -> matchesNamespace(request, "/ws");

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    DiscardingAuthorizedClientRepository discardingAuthorizedClientRepository() {
        return new DiscardingAuthorizedClientRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setParameterName("_csrf");
        return new SessionBoundCsrfTokenRepository(repository,
                request -> HttpMethod.GET.matches(request.getMethod())
                        && (request.getContextPath() + "/auth/dev/csrf").equals(request.getRequestURI()));
    }

    @Bean
    OAuthLoginSuccessHandler oauthLoginSuccessHandler(
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        return new OAuthLoginSuccessHandler(securityContextRepository, sessionAuthenticationStrategy);
    }

    @Bean
    SimpleUrlAuthenticationFailureHandler oauthAuthenticationFailureHandler() {
        SimpleUrlAuthenticationFailureHandler handler =
                new SimpleUrlAuthenticationFailureHandler("/login?loginError");
        // The SPA does not read the persisted exception. Never create a new
        // JDBC session just to retain an unauthenticated OAuth error.
        handler.setAllowSessionCreation(false);
        return handler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositories,
            GoogleOidcUserService googleOidcUserService,
            DiscordOAuth2UserService discordOAuth2UserService,
            OAuthLoginSuccessHandler oauthLoginSuccessHandler,
            SimpleUrlAuthenticationFailureHandler oauthAuthenticationFailureHandler,
            DiscardingAuthorizedClientRepository authorizedClientRepository,
            CsrfTokenRepository csrfTokenRepository,
            WebSocketSessionLogoutHandler webSocketSessionLogoutHandler,
            HardeningProperties hardeningProperties,
            @Value("${poker.admin.live.enabled:false}") boolean adminLiveEnabled) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // The JSON endpoint supplies one raw session token for
                        // both HTTP headers and the STOMP CONNECT header.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                // API and WebSocket authentication failures return 401 and the
                // SPA owns its post-login redirect, so no rejected request
                // should allocate a durable JDBC session just to be replayed.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> {
                    auth
                            .requestMatchers(HttpMethod.GET, "/api/health", "/api/meta", "/api/leaderboard", "/api/players/*").permitAll()
                            .requestMatchers(HttpMethod.GET,
                                    "/", "/login", "/index.html", "/favicon.ico", "/assets/**", "/room/**", "/leaderboard", "/players/*")
                            .permitAll()
                            .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/auth/dev/csrf").permitAll()
                            .requestMatchers(HttpMethod.POST, "/auth/dev").permitAll();
                    if (adminLiveEnabled) {
                        // The controller itself enforces loopback-only access
                        // and the mandatory shared admin token.
                        auth.requestMatchers(HttpMethod.GET, "/internal/admin/live/**").permitAll();
                    }
                    auth
                            .requestMatchers("/error", "/logout").permitAll()
                            .requestMatchers("/api/**", "/ws", "/ws/**").authenticated()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                API_REQUEST)
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                WEBSOCKET_REQUEST))
                .logout(logout -> logout
                        .addLogoutHandler(webSocketSessionLogoutHandler)
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION")
                        .logoutSuccessUrl("/"));

        if (clientRegistrationRepositories.getIfAvailable() != null) {
            http.addFilterBefore(
                    new OAuthInitiationRateLimitFilter(hardeningProperties),
                    OAuth2AuthorizationRequestRedirectFilter.class);
            http.oauth2Login(oauth -> oauth
                    // The SPA renders the sign-in screen at /login, so Spring must not
                    // install its own generated login page on that path.
                    .loginPage("/login")
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(googleOidcUserService)
                            .userService(discordOAuth2UserService))
                    .successHandler(oauthLoginSuccessHandler)
                    .failureHandler(oauthAuthenticationFailureHandler)
                    .authorizedClientRepository(authorizedClientRepository));
        }
        return http.build();
    }

    private static boolean matchesNamespace(jakarta.servlet.http.HttpServletRequest request, String root) {
        String path = request.getServletPath();
        return root.equals(path) || path.startsWith(root + "/");
    }
}
