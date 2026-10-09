package com.poker.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures transport-level evidence for endpoints that must never accept a
 * forwarded public request. Native/container forwarding must be disabled for
 * these endpoints: it changes the peer before any servlet filter can inspect it.
 */
public final class LoopbackRequestGuard extends OncePerRequestFilter implements Ordered {
    private static final String EVIDENCE_ATTRIBUTE = LoopbackRequestGuard.class.getName() + ".evidence";
    private final boolean localOnly;

    public LoopbackRequestGuard(boolean localOnly) {
        this.localOnly = localOnly;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Boot's ForwardedHeaderFilter also runs at HIGHEST_PRECEDENCE. Read
        // the underlying transport request even if that filter ran first: its
        // wrapper overrides getRemoteAddr and hides the forwarding headers.
        ServletRequest original = request;
        while (original instanceof ServletRequestWrapper wrapper) {
            original = wrapper.getRequest();
        }
        boolean local = original instanceof HttpServletRequest transport
                && isLoopbackAddress(transport.getRemoteAddr())
                && isLoopbackHost(transport.getServerName())
                && !hasForwardingHeaders(transport);
        request.setAttribute(EVIDENCE_ATTRIBUTE, new Evidence(local));
        if (localOnly && !local) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "local authentication requires a direct loopback request");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Fails closed if the transport filter has not inspected this request. */
    public static boolean strictlyLocal(HttpServletRequest request) {
        return request.getAttribute(EVIDENCE_ATTRIBUTE) instanceof Evidence evidence && evidence.local();
    }

    /** Tomcat can install RemoteIpValve through header settings even with strategy=none/framework. */
    public static boolean usesContainerForwarding(Environment environment) {
        Binder properties = Binder.get(environment);
        return "native".equalsIgnoreCase(properties.bind("server.forward-headers-strategy", String.class).orElse("none"))
                || !properties.bind("server.tomcat.remoteip.remote-ip-header", String.class).orElse("").isBlank()
                || !properties.bind("server.tomcat.remoteip.protocol-header", String.class).orElse("").isBlank();
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return isLoopbackAddress(host);
    }

    /** Accepts IP literals only; never resolves attacker-controlled DNS names. */
    public static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank()) return false;
        if (address.indexOf(':') >= 0) {
            if (!address.matches("[0-9a-fA-F:.]+")) return false;
        } else {
            if (!address.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private static boolean hasForwardingHeaders(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaderNames();
        if (headers == null) return true;
        while (headers.hasMoreElements()) {
            String header = headers.nextElement().toLowerCase(Locale.ROOT);
            if (header.equals("forwarded") || header.startsWith("x-forwarded-")) return true;
        }
        return false;
    }

    private record Evidence(boolean local) {}
}
