package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
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

import static org.mockito.Mockito.*;

@WebFluxTest(InverterController.class)
class InverterControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    InverterStreamService stream;

    private InverterSnapshot sample() {
        return InverterSnapshot.offline("SG10RS", "A24A0965660",
                Instant.parse("2026-07-25T08:00:00Z"), 0.5, null);
    }

    @Test
    void latestReturnsCurrentSnapshot() {
        when(stream.latest()).thenReturn(sample());

        web.get().uri("/api/inverter/latest")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deviceModel").isEqualTo("SG10RS")
                .jsonPath("$.serialNumber").isEqualTo("A24A0965660")
                .jsonPath("$.powerBalance.houseConsumptionKw").isEqualTo(0.5);
    }

    @Test
    void streamEmitsSnapshotsAsServerSentEvents() {
        when(stream.stream()).thenReturn(Flux.just(sample()));
        when(stream.latest()).thenReturn(sample());

        FluxExchangeResult<InverterSnapshot> result = web.get().uri("/api/inverter/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(InverterSnapshot.class);

        StepVerifier.create(result.getResponseBody())
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
