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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;

/**
 * Login endpoint (the one {@code /api/**} path left open by {@link io.dmitrykislov.miner.security.AuthWebFilter}).
 * A correct password returns a bearer token the UI stores; every other endpoint then
 * requires that token. There is no server-side logout — the UI simply discards its token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Dedicated, deliberately tiny scheduler for password hashing.
     *
     * <p>bcrypt must not run on a Netty event loop (there are only two on the Pi), but it must not run
     * on the shared {@code boundedElastic} either: that is the global scheduler, capped at 10× cores
     * with a ~100k task queue, and it is also where {@code /api/miner/stop} and the history endpoints
     * execute. An unauthenticated login flood would fill it and push the manual stop button minutes
     * deep behind attacker-supplied hashing. Two threads bound the CPU cost, and a short queue means
     * excess load is refused straight away instead of being absorbed.
     */
    private static final Scheduler LOGIN =
            Schedulers.newBoundedElastic(2, 16, "login-bcrypt", 60, true);

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
     * <p>bcrypt is deliberately slow — roughly 0.3-0.8 s per attempt on a Raspberry Pi — so an
     * unauthenticated caller repeatedly hitting this endpoint is a load problem, not just a guessing
     * problem. Three things bound it, and all three are needed:
     * <ol>
     *   <li>the attempt is reserved <b>atomically before hashing</b>, so concurrent requests cannot
     *       pipeline past a stale counter;</li>
     *   <li>hashing runs on {@link #LOGIN}, a two-thread scheduler of its own, so it can neither block
     *       a Netty event loop nor queue behind (or ahead of) the miner controls;</li>
     *   <li>when that small queue fills the request is refused with 503 rather than absorbed.</li>
     * </ol>
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody(required = false) LoginRequest req,
                                                    ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        // Reserve the attempt atomically BEFORE hashing, so concurrent requests can't all slip past a
        // stale counter and queue unbounded bcrypt work (see LoginRateLimiter.tryAcquire).
        if (!rateLimiter.tryAcquire(ip, System.currentTimeMillis())) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
        }
        String password = req != null ? req.password() : null;
        return Mono.fromCallable(() -> {
            if (auth.verifyPassword(password)) {
                rateLimiter.recordSuccess(ip);   // give the budget back to a legitimate user
                return ResponseEntity.ok(new LoginResponse(auth.issueToken()));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).<LoginResponse>build();
        })
        .subscribeOn(LOGIN)
        // The queue above is deliberately small. If it fills, shed load immediately rather than
        // letting pre-auth work pile up — the alternative is a backlog that delays real requests.
        .onErrorResume(RejectedExecutionException.class,
                e -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()));
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
