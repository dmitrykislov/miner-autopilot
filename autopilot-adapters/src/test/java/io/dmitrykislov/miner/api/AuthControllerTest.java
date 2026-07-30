package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.security.AuthService;
import io.dmitrykislov.miner.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/** The login endpoint: correct/incorrect password, and the per-IP brute-force rate limit (429). */
class AuthControllerTest {

    private AuthController controller;

    @BeforeEach
    void setup() {
        String hash = new BCryptPasswordEncoder().encode("secret");
        var props = new AuthProperties(true, hash, 30, 2); // allow 2 failed logins/min, then 429
        controller = new AuthController(new AuthService(props), new LoginRateLimiter(props));
    }

    private static ServerWebExchange from(String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").remoteAddress(new InetSocketAddress(ip, 40000)));
    }

    private int status(String password, ServerWebExchange ex) {
        return controller.login(new AuthController.LoginRequest(password), ex).getStatusCode().value();
    }

    @Test void correctPasswordReturnsAToken() {
        var resp = controller.login(new AuthController.LoginRequest("secret"), from("1.1.1.1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().token()).isNotBlank();
    }

    @Test void wrongPasswordIsUnauthorizedUntilTheLimitThen429() {
        var ex = from("2.2.2.2");
        assertThat(status("nope", ex)).isEqualTo(401);   // failure 1
        assertThat(status("nope", ex)).isEqualTo(401);   // failure 2 (limit = 2)
        assertThat(status("nope", ex)).isEqualTo(429);   // now rate-limited
        assertThat(status("secret", ex)).isEqualTo(429); // even a correct password is refused while blocked
    }

    @Test void aSuccessfulLoginClearsTheFailureBudget() {
        var ex = from("3.3.3.3");
        assertThat(status("nope", ex)).isEqualTo(401);   // one failure
        assertThat(status("secret", ex)).isEqualTo(200); // success resets the counter
        // budget restored → two more failures allowed before the 429
        assertThat(status("nope", ex)).isEqualTo(401);
        assertThat(status("nope", ex)).isEqualTo(401);
        assertThat(status("nope", ex)).isEqualTo(429);
    }

    @Test void differentIpsAreLimitedIndependently() {
        var a = from("4.4.4.4");
        assertThat(status("nope", a)).isEqualTo(401);
        assertThat(status("nope", a)).isEqualTo(401);
        assertThat(status("nope", a)).isEqualTo(429);            // IP a blocked
        assertThat(status("nope", from("5.5.5.5"))).isEqualTo(401); // IP b unaffected
    }

    @Test void sseTicketEndpointReturnsANamespacedTicket() {
        // The auth filter (not this controller) guards the endpoint, so reaching it means the caller
        // is already authenticated — it just hands back a short-lived, SSE-scoped ticket.
        var resp = controller.sseTicket();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().ticket()).startsWith("sse.");
    }
}
