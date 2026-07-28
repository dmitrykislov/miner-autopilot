package io.dmitrykislov.miner.braiins;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Transport-level tests for {@link BraiinsMinerClient} over a real {@code RestClient} (the layer the
 * mock-based {@link MinerServiceTest} can't exercise). Braiins OS+ answers some mutations — notably
 * {@code stop} — with a JSON body mislabelled {@code Content-Type: application/octet-stream}. A
 * {@code JsonNode} return type had no converter for that and threw even though the command
 * succeeded, so the autopilot logged "stop failed" while the miner actually stopped. These tests pin
 * the fix: the body is fetched as {@code byte[]} and parsed as JSON regardless of content type.
 */
class BraiinsTransportTest {

    private WireMockServer wm;
    private BraiinsMinerClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        var rc = RestClient.builder().baseUrl("http://localhost:" + wm.port()).requestFactory(factory).build();
        var adapter = RestClientAdapter.create(rc);
        BraiinsApi api = HttpServiceProxyFactory.builderFor(adapter).build().createClient(BraiinsApi.class);
        client = new BraiinsMinerClient(api);
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    private void stub(String op, String contentType, String body) {
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == '" + op + "')]"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)));
    }

    @Test
    void stopSucceedsWhenServerRespondsWithOctetStreamContentType() {
        // The exact failure mode observed on the Pi: a valid JSON stop result, content-type octet-stream.
        stub("WorkspaceBosStop", "application/octet-stream",
                "{\"data\":{\"bosminer\":{\"stop\":{\"__typename\":\"VoidResult\",\"void\":true}}}}");
        assertThatCode(() -> client.stop()).doesNotThrowAnyException();
    }

    @Test
    void statusParsesRegardlessOfOctetStreamContentType() {
        stub("Status", "application/octet-stream",
                "{\"data\":{\"bosminer\":{\"info\":{\"modelName\":\"S19k Pro\"},\"uptime\":null,\"config\":{}}}}");
        assertThat(client.status().path("info").path("modelName").asText()).isEqualTo("S19k Pro");
    }

    @Test
    void mutationToleratesAnEmptyBody() {
        // Some mutations may return 200 with no body — that is a success, not a parse failure.
        stub("WorkspaceBosStart", "application/octet-stream", "");
        assertThatCode(() -> client.start()).doesNotThrowAnyException();
    }

    @Test
    void graphQlErrorStillSurfacesEvenOverOctetStream() {
        // The error handling must still work when the (error) body is octet-stream-typed.
        stub("Status", "application/octet-stream", "{\"errors\":[{\"message\":\"Service unavailable\"}]}");
        assertThatCode(() -> client.status())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Service unavailable");
    }

    // ---- end-to-end through MinerService (transport + parse + service mapping) ----

    private MinerService service() {
        var props = new HouseProperties(null, null,
                new HouseProperties.Miner(true, "localhost:" + wm.port(), 10000, 8000, "", 0, 0), null);
        return new MinerService(client, new MinerStreamService(), props);
    }

    @Test
    void minerServiceStopSucceedsEndToEndOverOctetStream() {
        // Reproduces the live incident: stop mutation answered as octet-stream, then the stopped
        // miner's status query returns "Service unavailable". stop() must NOT report a failure — the
        // command applied — and must publish a clean off (no error).
        stub("WorkspaceBosStop", "application/octet-stream",
                "{\"data\":{\"bosminer\":{\"stop\":{\"__typename\":\"VoidResult\",\"void\":true}}}}");
        stub("Status", "application/octet-stream", "{\"errors\":[{\"message\":\"Service unavailable\"}]}");

        MinerStatus after = service().stop();

        assertThat(after.running()).isFalse();
        assertThat(after.error()).isNull(); // stopped ≠ errored
    }

    @Test
    void minerServiceReportsStoppedMinerAsCleanOffEndToEnd() {
        stub("Status", "application/json", "{\"errors\":[{\"message\":\"Service unavailable\"}]}");
        MinerStatus s = service().refresh();
        assertThat(s.state()).isEqualTo(MinerStatus.OFFLINE);
        assertThat(s.reachable()).isFalse();
        assertThat(s.error()).isNull();
    }
}
