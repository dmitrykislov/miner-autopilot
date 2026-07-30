package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.security.AuthService;
import io.dmitrykislov.miner.security.LoginRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;

/**
 * Login endpoint (the one {@code /api/**} path left open by {@link io.dmitrykislov.miner.security.AuthWebFilter}).
 * A correct password returns a bearer token the UI stores; every other endpoint then
 * requires that token. There is no server-side logout — the UI simply discards its token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final LoginRateLimiter rateLimiter;
    private final AuthProperties cfg;

    public AuthController(AuthService auth, LoginRateLimiter rateLimiter, AuthProperties cfg) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.cfg = cfg;
    }

    public record LoginRequest(String password) {}
    public record LoginResponse(String token) {}
    public record TicketResponse(String ticket) {}

    /**
     * Exchange a password for a bearer token.
     *
     * <p>The bcrypt comparison runs on {@link Schedulers#boundedElastic()} because it is deliberately
     * slow — roughly 0.3-0.8 s per attempt on a Raspberry Pi. WebFlux would otherwise run it on a
     * Netty event loop, and the Pi is configured with just <b>two</b> of them, so a couple of
     * concurrent attempts would freeze all HTTP and SSE traffic for everyone: dashboard, live charts
     * and the manual stop button included.
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody(required = false) LoginRequest req,
                                                    ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        long now = System.currentTimeMillis();
        // Brute-force guard: refuse (429) once this IP has failed too many times this minute, before
        // spending bcrypt on another guess — so a blocked caller costs almost nothing to turn away.
        if (!rateLimiter.allowed(ip, now)) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
        }
        String password = req != null ? req.password() : null;
        return Mono.fromCallable(() -> {
            if (auth.verifyPassword(password)) {
                rateLimiter.recordSuccess(ip);
                return ResponseEntity.ok(new LoginResponse(auth.issueToken()));
            }
            rateLimiter.recordFailure(ip, now);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).<LoginResponse>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Mint a short-lived ticket for opening SSE streams. Reaching here means the caller already
     * holds a valid full token (the auth filter guards this path), so no extra checks are needed.
     * The UI fetches a fresh ticket each time it (re)connects a stream — see {@link AuthService#issueSseTicket()}.
     */
    @PostMapping("/sse-ticket")
    public ResponseEntity<TicketResponse> sseTicket() {
        return ResponseEntity.ok(new TicketResponse(auth.issueSseTicket()));
    }

    /**
     * The client IP the rate limiter keys on: the socket address, or the first
     * {@code X-Forwarded-For} hop when {@code auth.trust-forwarded-for} is enabled.
     *
     * <p>That header is honoured only when explicitly enabled, because any client can send it. On a
     * directly-reachable box, trusting it unconditionally lets an attacker rotate the value to get a
     * fresh budget every request — defeating the limiter entirely — and forge a real user's address
     * to lock them out. Enable it only behind a proxy that overwrites the header.
     */
    private String clientIp(ServerWebExchange exchange) {
        if (cfg.trustForwardedFor()) {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
