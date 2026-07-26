package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
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
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@WebFluxTest(InverterController.class)
class InverterControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    InverterStreamService stream;

    private InverterSnapshot sample() {
        // online, metered: solar 3.0, house 2.0 ⇒ surplus 1.0, grid −1.0 (exporting)
        return new InverterSnapshot(true, "SG10RS", "A24A0965660", "Running",
                Instant.parse("2026-07-25T08:00:00Z"), Map.of(),
                PowerBalance.metered(3.0, 2.0), List.of(), List.of(), null);
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
                .jsonPath("$.powerBalance.gridPowerKw").isEqualTo(-1.0)
                .jsonPath("$.powerBalance.houseConsumptionKw").isEqualTo(2.0)
                .jsonPath("$.powerBalance.netSurplusKw").isEqualTo(1.0);
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
