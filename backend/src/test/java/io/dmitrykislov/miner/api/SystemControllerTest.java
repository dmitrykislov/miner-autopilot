package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.security.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

// The app's AuthWebFilter is a WebFilter, so @WebFluxTest wires it in — provide its
// deps and switch auth off; the login flow itself is covered by AuthSecurityTest.
@WebFluxTest(SystemController.class)
@Import(AuthService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.enabled=false")
class SystemControllerTest {

    @Autowired
    WebTestClient web;

    @Test
    void reportsVersionStartTimeAndUptime() {
        web.get().uri("/api/system")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").value(v -> {
                    // Sourced from the Maven project version (pom.xml) via @project.version@
                    // filtering — assert it's a real semver, not a brittle exact literal.
                    assert ((String) v).matches("\\d+\\.\\d+\\.\\d+.*");
                })
                .jsonPath("$.startedAt").value(v -> {
                    // ISO-8601 instant, e.g. 2026-07-26T01:39:06.830Z
                    assert ((String) v).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
                })
                .jsonPath("$.uptimeSeconds").value(v -> {
                    assert ((Number) v).longValue() >= 0;
                });
    }
}
