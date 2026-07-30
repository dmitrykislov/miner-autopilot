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

    private static final String HASH = new BCryptPasswordEncoder().encode("secret");

    private AuthController controller;

    @BeforeEach
    void setup() {
        controller = controllerWith(false); // allow 2 failed logins/min, then 429
    }

    /** A controller with the rate limit at 2/min and X-Forwarded-For trust set as given. */
    private static AuthController controllerWith(boolean trustForwardedFor) {
        var props = new AuthProperties(true, HASH, 30, 2, trustForwardedFor);
        return new AuthController(new AuthService(props), new LoginRateLimiter(props), props);
    }

    private static ServerWebExchange from(String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").remoteAddress(new InetSocketAddress(ip, 40000)));
    }

    /** Same socket IP for every request, but a caller-supplied X-Forwarded-For header. */
    private static ServerWebExchange spoofing(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("9.9.9.9", 40000))
                .header("X-Forwarded-For", forwardedFor));
    }

    private int status(String password, ServerWebExchange ex) {
        return status(controller, password, ex);
    }

    private static int status(AuthController c, String password, ServerWebExchange ex) {
        // login() is reactive now (bcrypt is offloaded off the event loop), so block for the result.
        return c.login(new AuthController.LoginRequest(password), ex).block().getStatusCode().value();
    }

    @Test void correctPasswordReturnsAToken() {
        var resp = controller.login(new AuthController.LoginRequest("secret"), from("1.1.1.1")).block();
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

    @Test void aSpoofedForwardedForHeaderCannotEscapeTheRateLimit() {
        // Default config does NOT trust X-Forwarded-For. An attacker rotating the header still shares
        // one budget (their real socket address), so the limiter bites on the third attempt.
        assertThat(status("nope", spoofing("1.2.3.1"))).isEqualTo(401);
        assertThat(status("nope", spoofing("1.2.3.2"))).isEqualTo(401);
        assertThat(status("nope", spoofing("1.2.3.3")))
                .as("rotating X-Forwarded-For must not hand out a fresh bcrypt budget")
                .isEqualTo(429);
    }

    @Test void aSpoofedForwardedForHeaderCannotLockOutAnotherUser() {
        // Burn the budget while forging a victim's address, then check the victim (arriving on their
        // own socket, no header) is unaffected.
        assertThat(status("nope", spoofing("7.7.7.7"))).isEqualTo(401);
        assertThat(status("nope", spoofing("7.7.7.7"))).isEqualTo(401);
        assertThat(status("nope", spoofing("7.7.7.7"))).isEqualTo(429); // attacker now blocked
        assertThat(status("secret", from("7.7.7.7")))
                .as("a forged header must not lock out the real owner of that address")
                .isEqualTo(200);
    }

    @Test void forwardedForIsHonouredWhenExplicitlyTrusted() {
        // Behind a proxy that overwrites the header, per-IP limiting must work off the header again.
        AuthController proxied = controllerWith(true);
        assertThat(status(proxied, "nope", spoofing("5.5.5.1"))).isEqualTo(401);
        assertThat(status(proxied, "nope", spoofing("5.5.5.1"))).isEqualTo(401);
        assertThat(status(proxied, "nope", spoofing("5.5.5.1"))).isEqualTo(429); // that hop is blocked
        assertThat(status(proxied, "nope", spoofing("5.5.5.2"))).isEqualTo(401); // a different hop is not
    }

    @Test void sseTicketEndpointReturnsANamespacedTicket() {
        // The auth filter (not this controller) guards the endpoint, so reaching it means the caller
        // is already authenticated — it just hands back a short-lived, SSE-scoped ticket.
        var resp = controller.sseTicket();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().ticket()).startsWith("sse.");
    }
}
