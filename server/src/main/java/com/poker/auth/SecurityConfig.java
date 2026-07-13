package com.poker.auth;

import com.poker.ws.WebSocketSessionLogoutHandler;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
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
        return repository;
    }

    @Bean
    OAuthLoginSuccessHandler oauthLoginSuccessHandler(
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        return new OAuthLoginSuccessHandler(securityContextRepository, sessionAuthenticationStrategy);
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
            DiscardingAuthorizedClientRepository authorizedClientRepository,
            CsrfTokenRepository csrfTokenRepository,
            WebSocketSessionLogoutHandler webSocketSessionLogoutHandler) throws Exception {
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/meta").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/favicon.ico", "/assets/**", "/lobby", "/room/**")
                        .permitAll()
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/dev").permitAll()
                        .requestMatchers("/error", "/logout").permitAll()
                        .requestMatchers("/api/**", "/ws", "/ws/**").authenticated()
                        .anyRequest().denyAll())
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
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(googleOidcUserService)
                            .userService(discordOAuth2UserService))
                    .successHandler(oauthLoginSuccessHandler)
                    .failureHandler(new SimpleUrlAuthenticationFailureHandler("/?loginError"))
                    .authorizedClientRepository(authorizedClientRepository));
        }
        return http.build();
    }

    private static boolean matchesNamespace(jakarta.servlet.http.HttpServletRequest request, String root) {
        String path = request.getServletPath();
        return root.equals(path) || path.startsWith(root + "/");
    }
}
