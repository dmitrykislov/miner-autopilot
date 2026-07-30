package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.security.AuthService;
import io.dmitrykislov.miner.security.LoginRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * Login endpoint (the one {@code /api/**} path left open by {@link io.dmitrykislov.miner.security.AuthWebFilter}).
 * A correct password returns a bearer token the UI stores; every other endpoint then
 * requires that token. There is no server-side logout — the UI simply discards its token.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private final AuthService auth;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthService auth, LoginRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(String password) {}
    public record LoginResponse(String token) {}

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody(required = false) LoginRequest req,
                                               ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        long now = System.currentTimeMillis();
        // Brute-force guard: refuse (429) once this IP has failed too many times this minute, before
        // spending bcrypt on another guess.
        if (!rateLimiter.allowed(ip, now)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String password = req != null ? req.password() : null;
        if (auth.verifyPassword(password)) {
            rateLimiter.recordSuccess(ip);
            return ResponseEntity.ok(new LoginResponse(auth.issueToken()));
        }
        rateLimiter.recordFailure(ip, now);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /** Client IP: the first hop in X-Forwarded-For when behind a reverse proxy, else the socket. */
    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
