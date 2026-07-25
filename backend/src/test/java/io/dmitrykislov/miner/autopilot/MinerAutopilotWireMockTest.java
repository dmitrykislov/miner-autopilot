package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.BraiinsClientConfig;
import io.dmitrykislov.miner.braiins.BraiinsMinerClient;
import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import io.dmitrykislov.miner.config.HouseProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.OptionalDouble;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * End-to-end autopilot test against a WireMock that simulates BOTH the Braiins
 * miner GraphQL API and the inverter/house power (the margin). Each scenario sets
 * a simulated margin + miner state, runs one autopilot tick, and asserts the exact
 * GraphQL mutation(s) the autopilot sent to the miner.
 */
class MinerAutopilotWireMockTest {

    private WireMockServer wm;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    private MinerService minerService;
    private MinerStreamService stream;
    private MinerAutopilot autopilot;

    @BeforeEach
    void setup() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        configureFor("localhost", wm.port());   // point the static stubFor/verify DSL at this server

        // --- fixed miner GraphQL stubs (matched by GraphQL operationName) ---
        stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Realtime')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"info\":{"
                        + "\"summary\":{\"realHashrate\":{\"mhs5S\":95000000},"
                        + "\"power\":{\"approxConsumptionW\":800,\"limitW\":900}},"
                        + "\"fans\":[{\"name\":\"0\",\"rpm\":3000,\"speed\":80}]}}}}")));
        stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'WorkspaceBosStart')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"start\":{\"__typename\":\"BosminerResult\"}}}}")));
        stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'WorkspaceBosStop')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"stop\":{\"__typename\":\"VoidResult\",\"void\":true}}}}")));
        stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'SettingsPerformanceEditWithTuning')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"config\":{\"updateAutotuning\":{\"__typename\":\"BosminerConfig\"}}}}}")));

        var props = props();
        var api = new BraiinsClientConfig().braiinsApi(props);
        minerService = new MinerService(new BraiinsMinerClient(api), stream = new MinerStreamService(), props);
        autopilot = new MinerAutopilot(simMargin(), minerService, stream, props);
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    private HouseProperties props() {
        return new HouseProperties(null, null, null,
                new HouseProperties.Miner(true, "localhost:" + wm.port(), 0, 0, "", 0, 0),
                new HouseProperties.Autopilot(true, 30000, 1000, 100, 1000));
    }

    /** Miner status stub for a given state (get_device_info / "Status" query). */
    private void stubMinerState(boolean running, int powerTargetW) {
        String uptime = running ? "{\"durationS\":600,\"since\":\"2026-07-25T00:00:00Z\"}" : "null";
        String pools = running ? "{\"url\":\"stratum\",\"active\":true}" : "";
        stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Status')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{"
                        + "\"info\":{\"modelName\":\"Antminer S19k Pro\",\"poolGroups\":[{\"pools\":[" + pools + "]}]},"
                        + "\"uptime\":" + uptime + ","
                        + "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":" + powerTargetW + "}}}}}")));
    }

    /** Simulated inverter+house margin, served by WireMock at /sim/power. */
    private void stubMargin(int solarW, int houseW) {
        stubFor(get("/sim/power").willReturn(okJson("{\"solarW\":" + solarW + ",\"houseW\":" + houseW + "}")));
    }

    private MarginSource simMargin() {
        return () -> {
            try {
                var resp = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + wm.port() + "/sim/power")).build(),
                        HttpResponse.BodyHandlers.ofString());
                var n = mapper.readTree(resp.body());
                return OptionalDouble.of(n.path("solarW").asDouble() - n.path("houseW").asDouble());
            } catch (Exception e) {
                return OptionalDouble.empty();
            }
        };
    }

    private void primeAndTick() {
        minerService.refresh();  // reads miner status from WireMock → stream.latest()
        autopilot.tick();        // decide + apply against WireMock
    }

    // ---- 1) START: miner off, margin 1500 → start at min (800) ----
    @Test
    void startsMinerWhenMarginSufficient() {
        stubMinerState(false, 800);      // stopped
        stubMargin(2000, 500);           // margin = 1500 ≥ 1000
        primeAndTick();
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("\"powerTarget\":800")));
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStart")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStop")));
    }

    @Test
    void doesNotStartWhenMarginTooLow() {
        stubMinerState(false, 800);
        stubMargin(1200, 500);           // margin = 700 < 1000
        primeAndTick();
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStart")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("updateAutotuning")));
    }

    // ---- 2) UPDATE POWER: running at 800, margin 1500 → step up to 1800 ----
    @Test
    void stepsPowerUpWhenSurplus() {
        stubMinerState(true, 800);
        stubMargin(3000, 1500);          // margin = 1500 ≥ 1000
        primeAndTick();
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("\"powerTarget\":1800")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStart")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStop")));
    }

    // ---- 2b) UPDATE POWER down: running at 2000, margin 50 → step down to 1000 ----
    @Test
    void stepsPowerDownWhenMarginLow() {
        stubMinerState(true, 2000);
        stubMargin(1000, 950);           // margin = 50 < 100
        primeAndTick();
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("\"powerTarget\":1000")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStop")));
    }

    // ---- 3) STOP: running at floor (800), margin 50 → stop ----
    @Test
    void stopsMinerAtFloorWhenMarginLow() {
        stubMinerState(true, 800);
        stubMargin(1000, 950);           // margin = 50 < 100, at min → stop
        primeAndTick();
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStop")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("updateAutotuning")));
    }

    // ---- step up caps at max: running at 3100, big margin → powerTarget 3600 ----
    @Test
    void stepUpCapsAtMaxPower() {
        stubMinerState(true, 3100);
        stubMargin(5000, 500);           // margin 4500 ≥ 1000
        primeAndTick();
        verify(postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("\"powerTarget\":3600")));
    }

    // ---- at max: no further step ----
    @Test
    void holdsAtMaxNoMutation() {
        stubMinerState(true, 3600);
        stubMargin(6000, 500);           // margin 5500 but already at max
        primeAndTick();
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("updateAutotuning")));
    }

    // ---- deadzone: running at 1800, margin 500 → no action ----
    @Test
    void holdsInDeadzone() {
        stubMinerState(true, 1800);
        stubMargin(2300, 1800);          // margin = 500 → deadzone
        primeAndTick();
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("updateAutotuning")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStart")));
        verify(0, postRequestedFor(urlEqualTo("/graphql")).withRequestBody(containing("WorkspaceBosStop")));
    }
}
