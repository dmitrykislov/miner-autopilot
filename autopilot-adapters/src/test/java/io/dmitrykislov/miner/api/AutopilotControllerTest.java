package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.autopilot.AutopilotStatus;
import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.autopilot.MinerAutopilot;
import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.security.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// AuthWebFilter is a WebFilter → @WebFluxTest wires it in; provide its deps + disable auth.
@WebFluxTest(AutopilotController.class)
@Import(AuthService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.enabled=false")
class AutopilotControllerTest {

    @Autowired WebTestClient web;
    @MockitoBean MinerAutopilot autopilot;
    @MockitoBean AutopilotStreamService stream;

    private static AutopilotStatus sample(boolean enabled) {
        Instant t = Instant.parse("2026-07-27T00:00:00Z");
        return new AutopilotStatus(enabled, t, "surplus 1500W ≥ 1000W → +1000W to 1800W",
                t, new AutopilotStatus.Change(t, "STEP_UP", 800, 1800, "surplus 1500W"));
    }

    @Test void statusReturnsCurrentStateAndLastChange() {
        when(autopilot.status()).thenReturn(sample(true));
        web.get().uri("/api/autopilot").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.lastDecision").isEqualTo("surplus 1500W ≥ 1000W → +1000W to 1800W")
                .jsonPath("$.lastChange.action").isEqualTo("STEP_UP")
                .jsonPath("$.lastChange.fromPowerW").isEqualTo(800)
                .jsonPath("$.lastChange.toPowerW").isEqualTo(1800);
    }

    @Test void enableTurnsItOnAndReturnsStatus() {
        when(autopilot.status()).thenReturn(sample(true));
        web.post().uri("/api/autopilot/enable").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.enabled").isEqualTo(true);
        verify(autopilot).setEnabled(true);
    }

    @Test void disableTurnsItOffAndReturnsStatus() {
        when(autopilot.status()).thenReturn(sample(false));
        web.post().uri("/api/autopilot/disable").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.enabled").isEqualTo(false);
        verify(autopilot).setEnabled(false);
    }

    @Test void streamIsServerSentEvents() {
        when(stream.stream()).thenReturn(Flux.just(sample(true)));
        when(stream.latest()).thenReturn(sample(true));
        web.get().uri("/api/autopilot/stream").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM);
    }
}
