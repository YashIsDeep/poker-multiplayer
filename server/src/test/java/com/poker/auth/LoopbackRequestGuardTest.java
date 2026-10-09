package com.poker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

class LoopbackRequestGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"})
    void numericLoopbackAddressesAreAccepted(String address) {
        assertThat(LoopbackRequestGuard.isLoopbackAddress(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "127.1", "0.0.0.0", "::", "203.0.113.10", "127.0.0.1.attacker.test"})
    void remoteAddressesAndDnsNamesAreRejected(String address) {
        assertThat(LoopbackRequestGuard.isLoopbackAddress(address)).isFalse();
    }

    @Test
    void evidenceIsRequiredRatherThanTrustingTheCurrentRequestAddress() {
        assertThat(LoopbackRequestGuard.strictlyLocal(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void originalTransportIsCheckedEvenWhenForwardedFilterAlreadyReplacedPeerAndRemovedHeaders() throws Exception {
        MockHttpServletRequest request = localRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Forwarded", "for=127.0.0.1;host=localhost;proto=http");
        AtomicBoolean reached = new AtomicBoolean();
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            HttpServletRequest forwarded = (HttpServletRequest) wrapped;
            assertThat(forwarded.getRemoteAddr()).isEqualTo("127.0.0.1");
            assertThat(forwarded.getHeader("Forwarded")).isNull();
            new LoopbackRequestGuard(false).doFilter(forwarded, response, (guarded, ignored) -> {
                reached.set(true);
                assertThat(LoopbackRequestGuard.strictlyLocal((HttpServletRequest) guarded)).isFalse();
            });
        });
        assertThat(reached).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Prefix"})
    void localProxyRequestsAreRejectedEvenWhenForwardingHeadersClaimLoopback(String header) throws Exception {
        MockHttpServletRequest request = localRequest();
        request.addHeader(header, header.equals("Forwarded") ? "for=127.0.0.1" : "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        new LoopbackRequestGuard(true).doFilter(request, response, (ignoredRequest, ignoredResponse) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(reached).isFalse();
    }

    @Test
    void tunnelHostIsRejectedEvenIfPeerIsLoopbackAndForwardingHeadersWereStripped() throws Exception {
        MockHttpServletRequest request = localRequest();
        request.setServerName("poker.example.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LoopbackRequestGuard(true).doFilter(request, response, failIfReached());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void remoteClientCannotGetPastWholeServerLocalAuthGuard() throws Exception {
        MockHttpServletRequest request = localRequest();
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LoopbackRequestGuard(true).doFilter(request, response, failIfReached());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void directLoopbackRequestRemainsUsable() throws Exception {
        MockHttpServletRequest request = localRequest();
        AtomicBoolean reached = new AtomicBoolean();
        new LoopbackRequestGuard(true).doFilter(request, new MockHttpServletResponse(), (guarded, ignored) -> {
            reached.set(true);
            assertThat(LoopbackRequestGuard.strictlyLocal((HttpServletRequest) guarded)).isTrue();
        });
        assertThat(reached).isTrue();
    }

    private static FilterChain failIfReached() {
        return (request, response) -> { throw new AssertionError("local-only request reached application"); };
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        return request;
    }
}
