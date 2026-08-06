package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.api.AuthController;
import io.dmitrykislov.miner.inverter.WiNetWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login rate limiter, exercised through the real HTTP stack.
 *
 * <p>Every other full-boot test sets {@code auth.login-max-per-minute=1000} (see the shared
 * test-classpath {@code application.properties}) so multi-login tests don't trip it. That left the
 * limiter's <b>wiring</b> untested: {@code LoginRateLimiterTest} proves the algorithm in isolation,
 * but nothing proved the controller actually consults it, returns 429, or derives the client IP the
 * way it should. A bug in any of those would have been invisible — which matters, because this is
 * the only thing standing between an unauthenticated caller and unbounded bcrypt work.
 *
 * <p>This class enables the limiter with a small real value and asserts the behaviour end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginRateLimitIntegrationTest {

    private static final String HASH = new BCryptPasswordEncoder().encode("secret");
    private static final int LIMIT = 3;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("auth.enabled", () -> true);
        r.add("auth.password-hash", () -> HASH);
        r.add("auth.login-max-per-minute", () -> LIMIT);   // override the relaxed shared default
        r.add("auth.trust-forwarded-for", () -> true);      // exercise the proxy path too
        r.add("house.solar-analytics.enabled", () -> false);
        r.add("house.inverter.poll-interval-ms", () -> 3_600_000);
        r.add("house.miner.poll-interval-ms", () -> 3_600_000);
        r.add("house.autopilot.enabled", () -> false);
    }

    @MockitoBean WiNetWebSocketClient winet;
    @Value("${local.server.port}") int port;

    private WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /** One login attempt from a caller whose proxied address is {@code forwardedFor}. */
    private int attempt(String password, String forwardedFor) {
        return web().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .bodyValue(new AuthController.LoginRequest(password))
                .exchange().returnResult(Void.class).getStatus().value();
    }

    @Test void theLimiterIsWiredIntoTheLoginEndpointAndReturns429() {
        String client = "203.0.113.10, 198.51.100.7";   // <client-supplied>, <appended by our proxy>
        for (int i = 0; i < LIMIT; i++) {
            assertThat(attempt("wrong", client))
                    .as("attempt %d of %d should still be checked", i + 1, LIMIT)
                    .isEqualTo(401);
        }
        assertThat(attempt("wrong", client))
                .as("past the limit the endpoint must refuse without hashing")
                .isEqualTo(429);
        assertThat(attempt("secret", client))
                .as("even a correct password is refused while the window is spent")
                .isEqualTo(429);
    }

    @Test void theLastForwardedHopIdentifiesTheClient() {
        // Caddy and nginx append the peer they saw, so the last element is ours and the earlier ones
        // are whatever the caller sent. An attacker varying only their own portion must share one
        // budget; a genuinely different client (different last hop) must get its own.
        assertThat(attempt("wrong", "1.1.1.1, 198.51.100.20")).isEqualTo(401);
        assertThat(attempt("wrong", "2.2.2.2, 198.51.100.20")).isEqualTo(401);
        assertThat(attempt("wrong", "3.3.3.3, 198.51.100.20")).isEqualTo(401);
        assertThat(attempt("wrong", "4.4.4.4, 198.51.100.20"))
                .as("rotating the client-supplied hop must not buy a fresh budget")
                .isEqualTo(429);

        assertThat(attempt("wrong", "4.4.4.4, 198.51.100.21"))
                .as("a different real client still has its own budget")
                .isEqualTo(401);
    }

    @Test void aSuccessfulLoginRefundsTheBudget() {
        String client = "203.0.113.30, 198.51.100.30";
        assertThat(attempt("wrong", client)).isEqualTo(401);
        assertThat(attempt("wrong", client)).isEqualTo(401);
        assertThat(attempt("secret", client)).isEqualTo(200);   // refunds

        // Full budget again, so the limit is reached only after LIMIT more attempts.
        for (int i = 0; i < LIMIT; i++) {
            assertThat(attempt("wrong", client)).isEqualTo(401);
        }
        assertThat(attempt("wrong", client)).isEqualTo(429);
    }
}
