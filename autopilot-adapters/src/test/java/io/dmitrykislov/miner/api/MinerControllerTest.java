package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.port.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.security.AuthService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.*;

@WebFluxTest(MinerController.class)
@Import(AuthService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.enabled=false")
class MinerControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    MinerService miner;

    @MockitoBean
    MinerStreamService stream;

    private MinerStatus status(boolean running) {
        return new MinerStatus(true, running, running ? "MINING" : "STOPPED", null, "Antminer S19k Pro", 1200, true,
                running ? 1 : 0, 1, running ? 95.0 : null, running ? 1150 : null, java.util.List.of(),
                running ? 3600L : null, Instant.parse("2026-07-25T08:00:00Z"), null);
    }

    @Test
    void statusReturnsLatest() {
        when(stream.latest()).thenReturn(status(false));
        web.get().uri("/api/miner/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.running").isEqualTo(false)
                .jsonPath("$.model").isEqualTo("Antminer S19k Pro")
                .jsonPath("$.powerTargetW").isEqualTo(1200);
    }

    @Test
    void startStopDelegateToService() {
        when(miner.start()).thenReturn(status(true));
        web.post().uri("/api/miner/start").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.running").isEqualTo(true);
        verify(miner).start();

        when(miner.stop()).thenReturn(status(false));
        web.post().uri("/api/miner/stop").exchange().expectStatus().isOk();
        verify(miner).stop();
    }

    @Test
    void powerPassesWattsAndApply() {
        when(miner.setPowerTarget(1400, true)).thenReturn(status(false));
        web.post().uri("/api/miner/power?watts=1400&apply=true").exchange()
                .expectStatus().isOk();
        verify(miner).setPowerTarget(1400, true);
    }

    @Test
    void streamEmitsStatusAsSse() {
        when(stream.stream()).thenReturn(Flux.just(status(true)));
        when(stream.latest()).thenReturn(status(true));
        FluxExchangeResult<MinerStatus> res = web.get().uri("/api/miner/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(MinerStatus.class);
        StepVerifier.create(res.getResponseBody()).expectNextCount(1).thenCancel().verify(Duration.ofSeconds(5));
    }
}
