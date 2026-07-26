package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.api.AuthController;
import io.dmitrykislov.miner.inverter.WiNetWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end access-control test: boots the full context with auth ENABLED and a known
 * bcrypt hash, and drives the real {@link AuthWebFilter} + {@link AuthController} over HTTP.
 * The hash is generated at runtime, so no real credential is committed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthSecurityTest {

    static final String HASH = new BCryptPasswordEncoder().encode("secret");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("auth.enabled", () -> true);
        r.add("auth.password-hash", () -> HASH);
        // keep the context light — no real devices, schedulers effectively idle
        r.add("house.solar-analytics.enabled", () -> false);
        r.add("house.inverter.poll-interval-ms", () -> 3_600_000);
        r.add("house.miner.poll-interval-ms", () -> 3_600_000);
        r.add("house.autopilot.enabled", () -> false);
    }

    @MockitoBean WiNetWebSocketClient winet; // no real inverter connection
    @Value("${local.server.port}") int port;

    private WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test void protectedEndpointRejectedWithoutToken() {
        web().get().uri("/api/system").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/inverter/latest").exchange().expectStatus().isUnauthorized();
    }

    @Test void loginEndpointIsOpenButRejectsWrongPassword() {
        web().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthController.LoginRequest("wrong"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test void loginThenAccessWithBearerToken() {
        String token = login("secret");
        assertThat(token).isNotBlank();
        web().get().uri("/api/system")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk();
    }

    @Test void queryParamTokenWorksForSseAndBogusIsRejected() {
        String token = login("secret");
        // EventSource can't set headers, so the token must also work as ?token=
        web().get().uri("/api/system?token=" + token).exchange().expectStatus().isOk();
        web().get().uri("/api/system?token=bogus").exchange().expectStatus().isUnauthorized();
    }

    @Test void writeEndpointRequiresTokenAndWorksWithOne() {
        // A state-changing endpoint must be gated too, and reachable with a valid token
        // (the miner isn't connected here, so we only assert auth passed — not 401).
        web().post().uri("/api/miner/stop").exchange().expectStatus().isUnauthorized();
        web().post().uri("/api/miner/stop")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("secret"))
                .exchange().expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
    }

    @Test void encodedApiPathStillRequiresToken() {
        // Regression for the decoded-path bypass: an encoded "/api" (%61 = 'a') must NOT slip
        // past the filter as a "static" path while the router still decodes it to a controller.
        web().post().uri(java.net.URI.create("http://localhost:" + port + "/%61pi/miner/stop"))
                .exchange().expectStatus().isUnauthorized();
        web().get().uri(java.net.URI.create("http://localhost:" + port + "/%61pi/system"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test void optionsPreflightIsOpen() {
        web().options().uri("/api/system").exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
    }

    private String login(String password) {
        AuthController.LoginResponse body = web().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthController.LoginRequest(password))
                .exchange().expectStatus().isOk()
                .expectBody(AuthController.LoginResponse.class)
                .returnResult().getResponseBody();
        return body != null ? body.token() : null;
    }
}
