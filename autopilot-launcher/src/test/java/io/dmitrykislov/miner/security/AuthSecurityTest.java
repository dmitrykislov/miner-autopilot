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
import reactor.test.StepVerifier;

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

    @Test void fullTokenIsRejectedInTheUrlAndAsAnSseTicket() {
        String token = login("secret");
        // The long-lived token must NOT authenticate via the URL (only the Authorization header) —
        // this is the whole point of the SSE-ticket scheme: no long-lived credential in a URL.
        web().get().uri("/api/system?token=" + token).exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/system?token=bogus").exchange().expectStatus().isUnauthorized();
    }

    @Test void sseTicketOpensAStreamButIsScopedToStreamPathsOnly() {
        String token = login("secret");
        String ticket = sseTicket(token);
        assertThat(ticket).startsWith("sse.");

        // Works on a stream endpoint via ?token= (EventSource can't set a header).
        var sse = web().get().uri("/api/power/stream?token=" + ticket)
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        StepVerifier.create(sse.getResponseBody()).expectNextCount(1).thenCancel().verify(Duration.ofSeconds(10));

        // But it is NOT a full token: rejected on a non-stream endpoint, and rejected as a Bearer header.
        web().get().uri("/api/system?token=" + ticket).exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/system").header(HttpHeaders.AUTHORIZATION, "Bearer " + ticket)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test void anSseTicketOnlyWorksForGet() {
        String ticket = sseTicket(login("secret"));
        // The ticket is a read-only credential. Even on a stream path, a non-GET method must not be
        // accepted with it — "read-only" is enforced, not merely implied by which handlers exist.
        web().post().uri("/api/power/stream?token=" + ticket).exchange().expectStatus().isUnauthorized();
        web().delete().uri("/api/power/stream?token=" + ticket).exchange().expectStatus().isUnauthorized();
    }

    @Test void unauthenticatedRequestsAreRejectedNotJustNon200() {
        // Deliberately strict: assert 401 exactly. An earlier version of these checks asserted only
        // "not 200"/"not 401", which would also pass if the filter were deleted or the app 500'd.
        web().get().uri("/api/system").exchange().expectStatus().isUnauthorized();
        web().post().uri("/api/miner/stop").exchange().expectStatus().isUnauthorized();
        web().post().uri("/api/autopilot/enable").exchange().expectStatus().isUnauthorized();
        web().post().uri("/api/miner/power?watts=3000").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/history").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/history/energy").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/power/stream").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/house/stream").exchange().expectStatus().isUnauthorized();
    }

    @Test void browserHardeningHeadersArePresentEvenOnA401() {
        // The UI holds a long-lived bearer token in localStorage, so a script injection would be able
        // to exfiltrate it. CSP is the header that prevents that, and it must be on every response —
        // including rejections, which is why the filter runs ahead of the auth filter.
        web().get().uri("/api/system").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().value("Content-Security-Policy", csp -> {
                    assertThat(csp).contains("default-src 'self'");
                    assertThat(csp).contains("frame-ancestors 'none'");
                    assertThat(csp).as("scripts must not be loadable from anywhere else")
                            .contains("script-src 'self'");
                });
    }

    @Test void hstsIsNotAdvertisedOverPlainHttp() {
        // These tests run without TLS. Announcing HSTS then would be meaningless, and would also be
        // wrong for someone deliberately running TLS_ENABLED=false on a LAN.
        web().get().uri("/api/system").exchange()
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }

    @Test void corsDoesNotAdvertiseAWildcardOrigin() {
        // A wildcard ACAO would let any website read API responses from a visitor's browser — and the
        // login endpoint needs no token, so it could brute-force through the victim's address.
        web().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "https://evil.example")
                .bodyValue(new AuthController.LoginRequest("wrong"))
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test void sseTicketEndpointItselfRequiresAFullToken() {
        web().post().uri("/api/auth/sse-ticket").exchange().expectStatus().isUnauthorized();
        web().post().uri("/api/auth/sse-ticket")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("secret"))
                .exchange().expectStatus().isOk();
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

    @Test void onlyExactLoginPathIsOpenAmongApiPaths() {
        // Login is opened by an EXACT match — near-misses must stay protected (401 without token).
        web().get().uri("/api/auth/loginX").exchange().expectStatus().isUnauthorized();
        web().get().uri("/api/auth/login/").exchange().expectStatus().isUnauthorized();
        // The real login path is open (no token needed); a GET has no handler (only POST),
        // so it's anything-but-401 (auth passed → 404/405), never an unauthenticated pass-through 401.
        web().get().uri("/api/auth/login").exchange()
                .expectStatus().value(s -> assertThat(s).isNotEqualTo(401));
    }

    @Test void doubleEncodedApiPathIsNeverUnauthenticatedSuccess() {
        // "/%2561pi" decodes once to the literal "%61pi" (no controller) — must not be a 200.
        web().get().uri(java.net.URI.create("http://localhost:" + port + "/%2561pi/system"))
                .exchange().expectStatus().value(s -> assertThat(s).isNotEqualTo(200));
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

    private String sseTicket(String token) {
        AuthController.TicketResponse body = web().post().uri("/api/auth/sse-ticket")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(AuthController.TicketResponse.class)
                .returnResult().getResponseBody();
        return body != null ? body.ticket() : null;
    }
}
