package io.dmitrykislov.miner.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Gates every {@code /api/**} request behind a valid bearer token (see {@link AuthService}).
 *
 * <p>Left open: static assets (anything not under {@code /api/}, so the SPA/login page can
 * load), the login endpoint itself, and CORS pre-flight ({@code OPTIONS}). The full token is read
 * only from the {@code Authorization: Bearer} header — never the URL, so a long-lived credential
 * can't leak into logs/history. Because {@code EventSource} (SSE) cannot set headers, the stream
 * endpoints additionally accept a short-lived {@code ?token=} <i>SSE ticket</i> (see
 * {@link AuthService#issueSseTicket()}). Rejections are a bare 401.
 *
 * <p>Path matching uses {@link PathPattern} against the request's parsed path — the <b>same</b>
 * representation the router uses — rather than the raw string. Matching the raw
 * (percent-encoded) path would let e.g. {@code /%61pi/miner/stop} look "non-API" to the filter
 * yet still route to the controller, bypassing auth entirely.
 */
@Component
public class AuthWebFilter implements WebFilter, Ordered {

    private static final PathPattern API_PATHS;
    private static final PathPattern LOGIN_PATH;
    private static final PathPattern STREAM_PATHS;
    static {
        // Default parser config to match the router's (case-sensitive, "/" separator, no
        // trailing-slash match). Soundness depends on this agreeing with WebFlux's parser — if
        // the app ever customizes WebFlux path matching, mirror it here or the two could diverge.
        PathPatternParser parser = new PathPatternParser();
        API_PATHS = parser.parse("/api/**");
        LOGIN_PATH = parser.parse("/api/auth/login");
        STREAM_PATHS = parser.parse("/api/*/stream"); // the SSE endpoints (…/power|inverter|miner|autopilot/stream)
    }

    private final AuthService auth;

    public AuthWebFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // run before controllers
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!auth.enabled()) return chain.filter(exchange);

        ServerHttpRequest req = exchange.getRequest();
        PathContainer path = req.getPath().pathWithinApplication();  // decoded, as the router sees it
        boolean open = HttpMethod.OPTIONS.equals(req.getMethod())   // CORS pre-flight
                || LOGIN_PATH.matches(path)
                || !API_PATHS.matches(path);                         // static assets / SPA
        if (open || auth.isValidToken(tokenOf(req))) {
            return chain.filter(exchange);
        }
        // Lesser credential: a short-lived SSE ticket in the query string, accepted only on the
        // streaming endpoints. It can open a stream but can't act as a full API token elsewhere.
        if (STREAM_PATHS.matches(path) && auth.isValidSseTicket(req.getQueryParams().getFirst("token"))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /** The full bearer token from the Authorization header only — never the URL (see class doc). */
    private static String tokenOf(ServerHttpRequest req) {
        String header = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        return null;
    }
}
