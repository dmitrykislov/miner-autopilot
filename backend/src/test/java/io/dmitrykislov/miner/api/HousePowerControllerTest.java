package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.powersensor.HouseConsumptionState;
import io.dmitrykislov.miner.powersensor.HousePower;
import io.dmitrykislov.miner.powersensor.HousePowerStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.when;

@WebFluxTest(HousePowerController.class)
class HousePowerControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    HousePowerStreamService stream;

    @MockitoBean
    HouseConsumptionState consumption;

    private HousePower sample() {
        return HousePower.measured(665.0, 241.3, "ecda3ba52594", Instant.parse("2026-07-25T08:00:00Z"));
    }

    @Test
    void latestReturnsCurrentReading() {
        when(stream.latest()).thenReturn(sample());

        web.get().uri("/api/house/latest")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.powerW").isEqualTo(665.0)
                .jsonPath("$.powerKw").isEqualTo(0.665)
                .jsonPath("$.metered").isEqualTo(true)
                .jsonPath("$.sourceMac").isEqualTo("ecda3ba52594");
    }

    @Test
    void streamEmitsReadingsAsServerSentEvents() {
        when(stream.stream()).thenReturn(Flux.just(sample()));
        when(stream.latest()).thenReturn(sample());

        FluxExchangeResult<HousePower> result = web.get().uri("/api/house/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(HousePower.class);

        StepVerifier.create(result.getResponseBody())
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
