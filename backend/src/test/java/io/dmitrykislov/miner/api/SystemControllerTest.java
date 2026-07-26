package io.dmitrykislov.miner.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(SystemController.class)
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
