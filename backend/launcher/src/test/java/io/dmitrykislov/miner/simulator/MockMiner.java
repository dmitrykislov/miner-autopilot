package io.dmitrykislov.miner.simulator;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * A simulated Braiins OS+ miner backed by WireMock (GraphQL over HTTP). Encapsulates
 * all request stubbing and verification so tests read as intent:
 * <pre>
 *   miner.mining(800);              // arrange state
 *   ...
 *   miner.verifyPowerSetTo(1800);   // assert the exact mutation
 * </pre>
 */
public class MockMiner {

    private final WireMockServer wm = new WireMockServer(options().dynamicPort());

    public MockMiner() { wm.start(); }

    /** {@code host:port} for {@code house.miner.host}. */
    public String host() { return "localhost:" + wm.port(); }

    public void stop() { wm.stop(); }

    /** Reset all requests + mappings and re-install the (constant) mutation stubs. */
    public void reset() {
        wm.resetAll();
        stubMutation("WorkspaceBosStart", "{\"data\":{\"bosminer\":{\"start\":{\"__typename\":\"BosminerResult\"}}}}");
        // Real Braiins OS+ answers the stop mutation with a JSON body mislabelled octet-stream — mirror
        // that here so the e2e suite exercises the transport's content-type tolerance, not just JSON.
        stubMutationWithContentType("WorkspaceBosStop", "application/octet-stream",
                "{\"data\":{\"bosminer\":{\"stop\":{\"__typename\":\"VoidResult\",\"void\":true}}}}");
        stubMutation("SettingsPerformanceEditWithTuning",
                "{\"data\":{\"bosminer\":{\"config\":{\"updateAutotuning\":{\"__typename\":\"BosminerConfig\"}}}}}");
    }

    /** Forget requests recorded so far (so verifications count only what follows). */
    public void clearRequests() { wm.resetRequests(); }

    // ---- arrange state ------------------------------------------------------

    /** Stopped: BOSMiner service down. */
    public void stopped(int powerTargetW) { status(false, false, powerTargetW); }

    /** Mining: service up and hashing. */
    public void mining(int powerTargetW) { status(true, true, powerTargetW); }

    /** Suspended: service up but paused (dead pools) — draws ~0 W. */
    public void suspended(int powerTargetW) { status(true, false, powerTargetW); }

    /** Unreachable: the API returns 5xx. */
    public void unreachable() {
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Status')]"))
                .willReturn(aResponse().withStatus(500)));
    }

    // ---- verify -------------------------------------------------------------

    public void verifyStarted()          { verifyOp("WorkspaceBosStart", 1); }
    public void verifyStopped()          { verifyOp("WorkspaceBosStop", 1); }
    public void verifyPowerSetTo(int w)  { wm.verify(postRequestedFor(url()).withRequestBody(containing("\"powerTarget\":" + w))); }
    public void verifyNoPowerChange()    { verifyOp("updateAutotuning", 0); }
    public void verifyNoStartOrStop()    { verifyOp("WorkspaceBosStart", 0); verifyOp("WorkspaceBosStop", 0); }
    /** No mutating call of any kind was sent to the miner. */
    public void verifyNoMutations()      { verifyNoStartOrStop(); verifyNoPowerChange(); }

    // ---- internals ----------------------------------------------------------

    private void status(boolean serviceUp, boolean mining, int powerW) {
        long mhs = mining ? 95_000_000L : 0L; // 0 → not hashing → suspended (when service up)
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Realtime')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{\"info\":{"
                        + "\"summary\":{\"realHashrate\":{\"mhs5S\":" + mhs + "},"
                        + "\"power\":{\"approxConsumptionW\":" + powerW + ",\"limitW\":900}},\"fans\":[]}}}}")));
        String uptime = serviceUp ? "{\"durationS\":600,\"since\":\"2026-07-26T00:00:00Z\"}" : "null";
        String pools = mining ? "{\"url\":\"s\",\"active\":true}" : (serviceUp ? "{\"url\":\"s\",\"active\":false}" : "");
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == 'Status')]"))
                .willReturn(okJson("{\"data\":{\"bosminer\":{"
                        + "\"info\":{\"modelName\":\"S19k\",\"poolGroups\":[{\"pools\":[" + pools + "]}]},"
                        + "\"uptime\":" + uptime + ","
                        + "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":" + powerW + "}}}}}")));
    }

    private void stubMutation(String op, String responseJson) {
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == '" + op + "')]"))
                .willReturn(okJson(responseJson)));
    }

    private void stubMutationWithContentType(String op, String contentType, String body) {
        wm.stubFor(post("/graphql").withRequestBody(matchingJsonPath("$[?(@.operationName == '" + op + "')]"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)));
    }

    private void verifyOp(String op, int times) {
        wm.verify(times, postRequestedFor(url()).withRequestBody(containing(op)));
    }

    private static com.github.tomakehurst.wiremock.matching.UrlPattern url() { return urlEqualTo("/graphql"); }
}
