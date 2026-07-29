package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.security.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(String password) {}
    public record LoginResponse(String token) {}

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody(required = false) LoginRequest req) {
        String password = req != null ? req.password() : null;
        if (auth.verifyPassword(password)) {
            return ResponseEntity.ok(new LoginResponse(auth.issueToken()));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
