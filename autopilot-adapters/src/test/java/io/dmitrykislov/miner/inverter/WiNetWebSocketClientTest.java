package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WiNetWebSocketClientTest {

    private WiNetWebSocketClient newClient() {
        var props = new HouseProperties(
                new HouseProperties.Inverter("192.168.4.25", 443, "/ws/home/overview", "admin", "pw", 5000, 8000),
                null, null, null);
        return new WiNetWebSocketClient(props);
    }

    @Test
    void handleMessageCompletesMatchingPendingRequest() throws Exception {
        var client = newClient();
        CompletableFuture<JsonNode> real = client.awaitService("real");

        client.handleMessage("{\"result_code\":1,\"result_data\":{\"service\":\"real\",\"list\":[]}}");

        JsonNode resp = real.get(1, TimeUnit.SECONDS);
        assertThat(resp.path("result_code").asInt()).isEqualTo(1);
        assertThat(resp.path("result_data").path("service").asText()).isEqualTo("real");
    }

    @Test
    void shutdownReleasesPendingRequestsAndReportsDisconnected() {
        // @PreDestroy: on context close / redeploy, in-flight requests must be released (not left
        // hanging until their timeout) and the client must report itself disconnected.
        var client = newClient();
        CompletableFuture<JsonNode> real = client.awaitService("real");

        client.shutdown();

        assertThat(real.isCompletedExceptionally()).isTrue();
        assertThat(client.isConnected()).isFalse();
        // idempotent: a second shutdown is a harmless no-op.
        client.shutdown();
    }

    @Test
    void handleMessageRoutesByServiceToTheRightFuture() throws Exception {
        var client = newClient();
        CompletableFuture<JsonNode> real = client.awaitService("real");
        CompletableFuture<JsonNode> direct = client.awaitService("direct");

        client.handleMessage("{\"result_data\":{\"service\":\"direct\"}}");

        assertThat(direct.isDone()).isTrue();
        assertThat(real.isDone()).isFalse();
    }

    @Test
    void handleMessageIgnoresNonJsonAndUnknownService() {
        var client = newClient();
        CompletableFuture<JsonNode> real = client.awaitService("real");

        client.handleMessage("this is not json");
        client.handleMessage("{\"result_data\":{\"service\":\"something_else\"}}");
        client.handleMessage("{\"no_service\":true}");

        assertThat(real.isDone()).isFalse(); // still waiting, nothing spuriously completed
    }

    // ---- stale-session isolation ------------------------------------------------------------
    // sendClose() only STARTS a close handshake, so after a reconnect the old socket's reader thread
    // can still deliver frames, and its listener shares this client's `pending` map.

    @Test
    void aFrameFromASupersededSessionCannotCompleteANewRequest() throws Exception {
        var client = newClient();
        int oldSession = client.nextSession();   // the socket that is about to be replaced
        client.nextSession();                    // reconnect → this is the live session now
        CompletableFuture<JsonNode> real = client.awaitService("real");

        // A late 'real' response arrives on the DEAD socket, carrying a stale solar reading.
        client.handleMessage("{\"result_code\":1,\"result_data\":{\"service\":\"real\",\"stale\":true}}", oldSession);

        assertThat(real.isDone())
                .as("a stale frame must not satisfy the new session's request — it would publish "
                        + "old inverter data as a live reading into the autopilot")
                .isFalse();
    }

    @Test
    void aFrameFromTheCurrentSessionStillCompletesItsRequest() throws Exception {
        var client = newClient();
        int live = client.nextSession();
        CompletableFuture<JsonNode> real = client.awaitService("real");

        client.handleMessage("{\"result_code\":1,\"result_data\":{\"service\":\"real\"}}", live);

        assertThat(real.get(1, TimeUnit.SECONDS).path("result_code").asInt()).isEqualTo(1);
    }

    @Test
    void checkResultCodeThrowsOnSessionExpired() {
        JsonNode expired = JsonMapper.builder().build()
                .readTree("{\"result_code\":106,\"result_data\":{\"service\":\"devicelist\"}}");
        assertThatThrownBy(() -> WiNetWebSocketClient.checkResultCode(expired, "devicelist"))
                .isInstanceOf(WiNetWebSocketClient.SessionExpiredException.class)
                .hasMessageContaining("106");
    }

    @Test
    void checkResultCodePassesOnSuccessAndMissingCode() {
        var mapper = JsonMapper.builder().build();
        // Assert explicitly that these do NOT throw. Previously this test called the method twice with
        // no assertion at all, so it passed whatever the method did short of throwing.
        assertThatNoException().isThrownBy(() ->
                WiNetWebSocketClient.checkResultCode(mapper.readTree("{\"result_code\":1}"), "real"));
        assertThatNoException().isThrownBy(() ->            // missing code defaults to success (1)
                WiNetWebSocketClient.checkResultCode(mapper.readTree("{}"), "real"));
        // A non-success, non-expiry code is currently tolerated too — pinning that so a future change
        // to stricter checking is a deliberate decision rather than a surprise.
        assertThatNoException().isThrownBy(() ->
                WiNetWebSocketClient.checkResultCode(mapper.readTree("{\"result_code\":215}"), "real"));
    }

    @Test
    void notConnectedInitially() {
        assertThat(newClient().isConnected()).isFalse();
    }
}
