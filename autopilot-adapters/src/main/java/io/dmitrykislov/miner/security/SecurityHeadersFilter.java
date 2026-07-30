package io.dmitrykislov.miner.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds the standard browser hardening headers to every response.
 *
 * <p>The app keeps its bearer token in {@code localStorage} and that token cannot be revoked before it
 * expires, so a single script-injection — from an XSS hole or a compromised npm dependency — would be
 * enough to exfiltrate a credential good for {@code AUTH_TOKEN_TTL_DAYS}. The <b>Content-Security-Policy
 * is the header that matters most here</b>: it confines scripts, styles and connections to this origin,
 * so injected code has nowhere to send anything.
 *
 * <p>The policy allows inline styles because the UI sets element styles directly for the animated power
 * flow; it does <b>not</b> allow inline or remote scripts. {@code connect-src 'self'} covers both fetch
 * and the SSE streams. Frame embedding is denied outright (there is nothing here worth framing, and it
 * removes clickjacking of the miner controls).
 *
 * <p>HSTS is sent only on requests that arrived over TLS — announcing it over plain HTTP is meaningless,
 * and it must not be set when someone deliberately runs with {@code TLS_ENABLED=false} on a LAN. No
 * {@code preload} and no {@code includeSubDomains}: this is one host, often behind a proxy on a domain
 * shared with other things, and both directives are hard to walk back.
 */
@Component
public class SecurityHeadersFilter implements WebFilter, Ordered {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",   // the dashboard sets inline styles for the flow animation
            "img-src 'self' data:",
            "connect-src 'self'",                 // fetch + EventSource
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'");

    @Override
    public int getOrder() {
        // Before the auth filter, so even a 401 carries the headers.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders h = exchange.getResponse().getHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        if (isSecure(exchange)) {
            h.set("Strict-Transport-Security", "max-age=31536000");
        }
        return chain.filter(exchange);
    }

    /** True when the request reached us over TLS, directly or via a proxy that said so. */
    private static boolean isSecure(ServerWebExchange exchange) {
        var uri = exchange.getRequest().getURI();
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        // Behind a TLS-terminating proxy the scheme is http here; honour its declaration. This only
        // controls whether HSTS is advertised, so a spoofed value costs nothing.
        return "https".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"));
    }
}
