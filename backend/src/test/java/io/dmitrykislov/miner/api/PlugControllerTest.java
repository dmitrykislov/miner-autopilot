package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.plug.PlugService;
import io.dmitrykislov.miner.plug.PlugStatus;
import io.dmitrykislov.miner.plug.PlugStreamService;
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

@WebFluxTest(PlugController.class)
class PlugControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    PlugService plug;

    @MockitoBean
    PlugStreamService stream;

    private PlugStatus status(boolean on) {
        return new PlugStatus(true, on, "Living Room", "P110", 42.5, 310.0,
                Instant.parse("2026-07-25T08:00:00Z"), null);
    }

    @Test
    void statusReturnsLatest() {
        when(stream.latest()).thenReturn(status(true));
        web.get().uri("/api/plug/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.on").isEqualTo(true)
                .jsonPath("$.name").isEqualTo("Living Room")
                .jsonPath("$.currentPowerW").isEqualTo(42.5);
    }

    @Test
    void onCallsServiceAndReturnsStatus() {
        when(plug.setOn(true)).thenReturn(status(true));
        web.post().uri("/api/plug/on").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.on").isEqualTo(true);
        verify(plug).setOn(true);
    }

    @Test
    void offCallsService() {
        when(plug.setOn(false)).thenReturn(status(false));
        web.post().uri("/api/plug/off").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.on").isEqualTo(false);
        verify(plug).setOn(false);
    }

    @Test
    void toggleCallsService() {
        when(plug.toggle()).thenReturn(status(false));
        web.post().uri("/api/plug/toggle").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.on").isEqualTo(false);
        verify(plug).toggle();
    }

    @Test
    void streamEmitsStatusAsSse() {
        when(stream.stream()).thenReturn(Flux.just(status(true)));
        when(stream.latest()).thenReturn(status(true));
        FluxExchangeResult<PlugStatus> res = web.get().uri("/api/plug/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(PlugStatus.class);
        StepVerifier.create(res.getResponseBody())
                .expectNextCount(1).thenCancel().verify(Duration.ofSeconds(5));
    }
}
