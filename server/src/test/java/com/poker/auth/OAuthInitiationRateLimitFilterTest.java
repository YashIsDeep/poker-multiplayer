package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.poker.hardening.HardeningProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OAuthInitiationRateLimitFilterTest {
    @Test
    void limitsGetInitiationRequestsPerPeerWithoutCreatingSessions() throws Exception {
        HardeningProperties properties = new HardeningProperties();
        properties.setOauthInitiationRate(1);
        properties.setOauthInitiationBurst(2);
        properties.setOauthInitiationRegistryCap(4);
        properties.setOauthInitiationRegistryTtl(Duration.ofMinutes(1));
        OAuthInitiationRateLimitFilter filter = new OAuthInitiationRateLimitFilter(
                properties, () -> 0L);
        AtomicInteger forwarded = new AtomicInteger();

        assertThat(run(filter, "192.0.2.10", "/oauth2/authorization/google", "GET", forwarded)
                .getStatus()).isEqualTo(200);
        assertThat(run(filter, "192.0.2.10", "/oauth2/authorization/google", "GET", forwarded)
                .getStatus()).isEqualTo(200);

        MockHttpServletRequest limitedRequest = request(
                "192.0.2.10", "/oauth2/authorization/google", "GET");
        MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
        filter.doFilter(limitedRequest, limitedResponse, (request, response) -> forwarded.incrementAndGet());

        assertThat(limitedResponse.getStatus()).isEqualTo(429);
        assertThat(limitedResponse.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limitedRequest.getSession(false)).isNull();
        assertThat(forwarded).hasValue(2);

        assertThat(run(filter, "192.0.2.11", "/oauth2/authorization/google", "GET", forwarded)
                .getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotConsumeTokensForOtherRequestsOrMethods() throws Exception {
        HardeningProperties properties = new HardeningProperties();
        properties.setOauthInitiationRate(1);
        properties.setOauthInitiationBurst(1);
        OAuthInitiationRateLimitFilter filter = new OAuthInitiationRateLimitFilter(
                properties, new AtomicLong()::get);
        AtomicInteger forwarded = new AtomicInteger();

        assertThat(run(filter, "192.0.2.20", "/login/oauth2/code/google", "GET", forwarded)
                .getStatus()).isEqualTo(200);
        assertThat(run(filter, "192.0.2.20", "/oauth2/authorization/google", "POST", forwarded)
                .getStatus()).isEqualTo(200);
        assertThat(run(filter, "192.0.2.20", "/oauth2/authorization/google", "GET", forwarded)
                .getStatus()).isEqualTo(200);
        assertThat(forwarded).hasValue(3);
    }

    private static MockHttpServletResponse run(
            OAuthInitiationRateLimitFilter filter,
            String peer,
            String path,
            String method,
            AtomicInteger forwarded) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(peer, path, method), response,
                (request, ignored) -> forwarded.incrementAndGet());
        return response;
    }

    private static MockHttpServletRequest request(String peer, String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(peer);
        request.setServletPath(path);
        return request;
    }
}
