package io.dmitrykislov.miner.solaranalytics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import io.dmitrykislov.miner.port.ConsumptionSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SolarAnalyticsClientTest {

    @Test
    void theCloudClientVerifiesTheServerHostname() {
        // The app disables hostname verification JVM-wide for the WiNet dongle's SAN-less self-signed
        // cert. This client carries the account email + password to a PUBLIC endpoint, so it must opt
        // back in explicitly, or any CA-issued cert for any name would be accepted by a MITM.
        assertThat(SolarAnalyticsClient.newVerifyingHttpClient().sslParameters()
                .getEndpointIdentificationAlgorithm())
                .as("the credential-bearing cloud client must check the hostname regardless of the global flag")
                .isEqualTo("HTTPS");
    }

    @Test
    void theGlobalKillSwitchWouldOtherwiseDisableThatCheck() {
        // Pins WHY the explicit setting is needed: a default-built client inherits the global flag, so
        // it ends up with NO endpoint identification at all. Assert null specifically — isNotEqualTo
        // ("HTTPS") also passes when the property had no effect, so it proved nothing.
        final String key = "jdk.internal.httpclient.disableHostnameVerification";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            assertThat(java.net.http.HttpClient.newHttpClient().sslParameters()
                    .getEndpointIdentificationAlgorithm())
                    .as("a default client inherits the global disable — hence the explicit override")
                    .isNull();
        } finally {
            // Restore it. Leaving it set leaked hostname-verification-off into every later test in this
            // module — the exact setting the test above exists to guard.
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }


    private final JsonMapper mapper = JsonMapper.builder().build();

    // ---- response parsing (pure) -------------------------------------------

    @Test
    void extractsNewestConsumedWatts() {
        var j = mapper.readTree("{\"data\":["
                + "{\"consumed\":903,\"generated\":200,\"t_stamp\":\"2026-07-26T06:36:30Z\"},"
                + "{\"consumed\":958,\"generated\":221,\"t_stamp\":\"2026-07-26T06:37:05Z\"}],"
                + "\"site_timezone\":\"Australia/Brisbane\"}");
        assertThat(SolarAnalyticsClient.latestConsumedWatts(j)).isEqualTo(958.0);
    }

    @Test
    void nullWhenNoData() {
        assertThat(SolarAnalyticsClient.latestConsumedWatts(mapper.readTree("{\"data\":[]}"))).isNull();
        assertThat(SolarAnalyticsClient.latestConsumedWatts(mapper.readTree("{}"))).isNull();
    }

    @Test
    void nullWhenLatestSampleHasNoConsumed() {
        var j = mapper.readTree("{\"data\":[{\"generated\":200,\"t_stamp\":\"2026-07-26T06:36:30Z\"}]}");
        assertThat(SolarAnalyticsClient.latestConsumedWatts(j)).isNull();
    }

    // ---- poll() outage handling (WireMock) ---------------------------------

    private static final String LIVE = "/live_site_data";

    private WireMockServer wm;
    private HouseConsumptionState consumption;
    private HousePowerStreamService stream;
    private InverterStreamService inverter;
    private ConsumptionSource consumptionSource;
    private SolarAnalyticsClient client;

    @BeforeEach
    void setup() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        consumption = mock(HouseConsumptionState.class);
        stream = mock(HousePowerStreamService.class);
        inverter = mock(InverterStreamService.class);
        consumptionSource = mock(ConsumptionSource.class);
        // Solar 3.0 kW = 3000 W, comfortably above the 800 W gate, so every poll makes the API call.
        when(inverter.latest()).thenReturn(new InverterSnapshot(true, "SG10RS", "SN", "Running",
                Instant.parse("2026-07-28T00:00:00Z"), Map.of(), PowerBalance.metered(3.0, 1.0),
                List.of(), List.of(), null));
        var sa = new HouseProperties.SolarAnalytics(true, "http://localhost:" + wm.port(),
                "user", "pass", "12345", 15000, 60, 2000, 800);
        client = new SolarAnalyticsClient(new HouseProperties(null, sa, null, null),
                consumption, stream, inverter, consumptionSource);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void ridesOutBriefFailuresThenMarksUnavailableAfterThreeInARow() {
        wm.stubFor(get(urlPathEqualTo(LIVE)).willReturn(aResponse().withStatus(500)));

        client.poll();
        client.poll();
        verify(consumptionSource, never()).clear();          // first blips are ridden out

        client.poll();                                        // third consecutive failure
        verify(consumptionSource, times(1)).clear();          // now marked unavailable → surplus unknown
        verify(consumptionSource, never()).publish(any());    // never fed a bogus reading to the engine
    }

    @Test
    void aSuccessfulPollResetsTheConsecutiveFailureCounter() {
        // fail, fail, SUCCESS, fail, fail — the success in the middle must reset the counter, so the
        // two trailing failures never reach three-in-a-row and the port is never cleared.
        wm.stubFor(get(urlPathEqualTo(LIVE)).inScenario("sa").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("f2"));
        wm.stubFor(get(urlPathEqualTo(LIVE)).inScenario("sa").whenScenarioStateIs("f2")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("ok"));
        wm.stubFor(get(urlPathEqualTo(LIVE)).inScenario("sa").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"data\":[{\"consumed\":950}]}")).willSetStateTo("f4"));
        wm.stubFor(get(urlPathEqualTo(LIVE)).inScenario("sa").whenScenarioStateIs("f4")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("f5"));
        wm.stubFor(get(urlPathEqualTo(LIVE)).inScenario("sa").whenScenarioStateIs("f5")
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 5; i++) client.poll();

        verify(consumptionSource, never()).clear();            // never three consecutive → never cleared
        verify(consumptionSource, times(1)).publish(any());     // exactly the one successful reading
    }

    @Test
    void aSuccessfulPollFeedsTheReadingToThePort() {
        wm.stubFor(get(urlPathEqualTo(LIVE)).willReturn(okJson("{\"data\":[{\"consumed\":1234}]}")));

        client.poll();

        verify(consumptionSource, times(1)).publish(argThat(r -> r.watts() == 1234.0));
        verify(consumptionSource, never()).clear();
    }

    @Test
    void a200WithNoUsableDataCountsAsAFailureAndClearsAfterThree() {
        // A 200 with an empty data array yields no reading — it must be treated like a transport
        // failure (warn-once + clear-after-N), not silently leave the stale value in the port.
        wm.stubFor(get(urlPathEqualTo(LIVE)).willReturn(okJson("{\"data\":[]}")));

        client.poll();
        client.poll();
        verify(consumptionSource, never()).clear();
        verify(consumptionSource, never()).publish(any());   // never a bogus reading

        client.poll();                                        // third empty response
        verify(consumptionSource, times(1)).clear();
    }
}
