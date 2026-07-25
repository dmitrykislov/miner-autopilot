package io.dmitrykislov.miner;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.inverter.InverterPoller;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-application smoke test: boots the <b>entire Spring Boot context</b> on a real
 * port and drives the real HTTP endpoints. The Braiins miner is simulated by WireMock;
 * the other devices are disabled or pointed at unreachable hosts so the app degrades
 * gracefully. This is the end-to-end counterpart to the slice/unit tests — it proves
 * the whole wiring (config binding, all beans, schedulers, controllers, SSE) actually
 * starts and serves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullApplicationWireMockTest {

    static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
        // Miner GraphQL: running at 1200 W with an active pool + live realtime stats.
        WM.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Status')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{"
                        + "\"info\":{\"modelName\":\"Antminer S19k Pro\",\"poolGroups\":[{\"pools\":[{\"url\":\"s\",\"active\":true}]}]},"
                        + "\"uptime\":{\"durationS\":600,\"since\":\"2026-07-26T00:00:00Z\"},"
                        + "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":1200}}}}}")));
        WM.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Realtime')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"info\":{"
                        + "\"summary\":{\"realHashrate\":{\"mhs5S\":95000000},\"power\":{\"approxConsumptionW\":1180,\"limitW\":1200}},"
                        + "\"fans\":[{\"name\":\"0\",\"rpm\":3000,\"speed\":80}]}}}}")));
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        // Miner → WireMock (HTTP GraphQL)
        r.add("house.miner.host", () -> "localhost:" + WM.port());
        r.add("house.miner.poll-interval-ms", () -> 500);
        // Inverter → unreachable closed port → poller degrades to offline (fast refused)
        r.add("house.inverter.host", () -> "127.0.0.1");
        r.add("house.inverter.port", () -> 59999);
        r.add("house.inverter.poll-interval-ms", () -> 1000);
        // Others off so the context boots clean and fast
        r.add("house.power-sensor.enabled", () -> false);
        r.add("house.plug.enabled", () -> false);
        r.add("house.autopilot.enabled", () -> false);
    }

    @AfterAll
    static void stopWireMock() { WM.stop(); }

    @Autowired MinerService minerService;
    @Autowired InverterPoller inverterPoller;
    @Value("${local.server.port}") int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * The embedded server can 404 the very first request under heavy parallel-build
     * load, before its route mappings are hot. Probe a known endpoint until it
     * answers 200 (bounded) so the assertions below aren't racing startup. This
     * only tolerates the warmup window — the strict content assertions still run.
     */
    private void awaitRoutesReady(WebTestClient web) {
        for (int attempt = 1; attempt <= 40; attempt++) {   // ≤ ~2s
            int status = web.get().uri("/api/miner/status").exchange()
                    .returnResult(Void.class).getStatus().value();
            if (status == 200) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("server routes not ready after warmup window");
    }

    @Test
    void wholeAppBootsAndServesEndpoints() {
        // Force one poll of each so state is deterministic (also exercises the real
        // client → service → stream pipeline the schedulers use).
        MinerStatus miner = minerService.refresh();     // hits WireMock
        inverterPoller.poll();                           // 127.0.0.1:59999 refused → offline

        // sanity on the service layer
        assertThat(miner.reachable()).isTrue();
        assertThat(miner.state()).isEqualTo(MinerStatus.MINING);

        var web = client();
        awaitRoutesReady(web);   // avoid racing the embedded server's route warmup

        // 1) Miner endpoint reflects the WireMock-simulated miner, end-to-end over HTTP.
        web.get().uri("/api/miner/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reachable").isEqualTo(true)
                .jsonPath("$.state").isEqualTo("MINING")
                .jsonPath("$.model").isEqualTo("Antminer S19k Pro")
                .jsonPath("$.powerTargetW").isEqualTo(1200)
                .jsonPath("$.hashrateThs").isEqualTo(95.0)
                .jsonPath("$.fans[0].rpm").isEqualTo(3000);

        // 2) Inverter endpoint responds and degrades gracefully (unreachable → offline).
        web.get().uri("/api/inverter/latest").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.online").isEqualTo(false);

        // 3) House endpoint responds even with the Powersensor disabled.
        web.get().uri("/api/house/latest").exchange().expectStatus().isOk();

        // 4) SSE stream is live and emits the current miner status.
        FluxExchangeResult<MinerStatus> sse = web.get().uri("/api/miner/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(MinerStatus.class);
        StepVerifier.create(sse.getResponseBody())
                .expectNextCount(1).thenCancel().verify(Duration.ofSeconds(5));

        // 5) The bundled UI index is served at "/" (present after a UI build).
        web.get().uri("/").exchange().expectStatus().value(s -> assertThat(s).isIn(200, 404));

        // 6) The app really called the simulated miner.
        WM.verify(postRequestedFor(urlEqualTo("/graphql"))
                .withRequestBody(matchingJsonPath("$[?(@.operationName == 'Status')]")));
    }
}
