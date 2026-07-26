package io.dmitrykislov.miner.simulator;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * A simulated Solar Analytics cloud backed by WireMock (HTTP). Serves the
 * {@code /live_site_data} endpoint the {@code SolarAnalyticsClient} polls.
 * <pre>
 *   solar.consumption(1000);  // whole-home draw = 1000 W
 *   solar.offline();          // API 5xx → consumption unavailable
 * </pre>
 */
public class MockSolarAnalytics {

    private final WireMockServer wm = new WireMockServer(options().dynamicPort());

    public MockSolarAnalytics() { wm.start(); }

    /** Base URL for {@code house.solar-analytics.host}. */
    public String baseUrl() { return "http://localhost:" + wm.port() + "/api/v3"; }

    public void stop() { wm.stop(); }

    public void reset() { wm.resetAll(); }

    /** Forget requests recorded so far (so verifications count only what follows). */
    public void clearRequests() { wm.resetRequests(); }

    /** Assert the consumption API WAS queried. */
    public void verifyFetched() {
        wm.verify(moreThanOrExactly(1), getRequestedFor(urlPathEqualTo("/api/v3/live_site_data")));
    }

    /** Assert the consumption API was NOT queried (e.g. gated off by low solar). */
    public void verifyNotFetched() {
        wm.verify(0, getRequestedFor(urlPathEqualTo("/api/v3/live_site_data")));
    }

    /** Live whole-home consumption reading (watts). */
    public void consumption(int watts) {
        wm.stubFor(get(urlPathEqualTo("/api/v3/live_site_data")).willReturn(okJson(
                "{\"data\":[{\"consumed\":" + watts + ",\"generated\":0,\"t_stamp\":\"2026-07-26T06:00:00Z\"}],"
                        + "\"site_timezone\":\"Australia/Brisbane\"}")));
    }

    /** API failing → no consumption reading available. */
    public void offline() {
        wm.stubFor(get(urlPathEqualTo("/api/v3/live_site_data")).willReturn(aResponse().withStatus(503)));
    }
}
