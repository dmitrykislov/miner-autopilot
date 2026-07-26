package io.dmitrykislov.miner.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gates every {@code /api/**} request behind a valid bearer token (see {@link AuthService}).
 *
 * <p>Left open: static assets (anything not under {@code /api/}, so the SPA/login page can
 * load), the login endpoint itself, and CORS pre-flight ({@code OPTIONS}). The token is read
 * from the {@code Authorization: Bearer} header, or — because {@code EventSource} (SSE) cannot
 * set headers — from a {@code ?token=} query parameter. Rejections are a bare 401.
 */
@Component
public class AuthWebFilter implements WebFilter, Ordered {

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
        String path = req.getPath().value();
        boolean open = HttpMethod.OPTIONS.equals(req.getMethod())   // CORS pre-flight
                || "/api/auth/login".equals(path)
                || !path.startsWith("/api/");                        // static assets / SPA
        if (open || auth.isValidToken(tokenOf(req))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private static String tokenOf(ServerHttpRequest req) {
        String header = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        return req.getQueryParams().getFirst("token"); // EventSource can't set headers
    }
}
