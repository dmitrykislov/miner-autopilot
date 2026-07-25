package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.inverter.dto.DeviceListResponse;
import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Low-level client for the Sungrow WiNet-S dongle's local WebSocket API
 * (the same protocol the phone app uses in AP mode).
 *
 * <p>Protocol handshake:
 * <ol>
 *   <li>{@code connect}    → yields an anonymous session token</li>
 *   <li>{@code login}      → authenticates and yields an elevated token</li>
 *   <li>{@code devicelist} → enumerates attached devices (the SG10RS)</li>
 *   <li>{@code real}/{@code direct}/... → real-time datasets</li>
 * </ol>
 *
 * <p>The dongle serves wss:// with a self-signed certificate, so TLS
 * verification is disabled for this LAN device. Requests are correlated to
 * responses by the {@code result_data.service} field; the class serialises
 * calls so only one request per service is ever in flight.
 */
@Component
public class WiNetWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WiNetWebSocketClient.class);

    static {
        // java.net.http.HttpClient verifies the server hostname against the
        // certificate SAN. The WiNet-S cert is self-signed with CN=Sun and no
        // SAN, so verification fails. This is a trusted device on the LAN, so we
        // disable hostname verification for the HTTP client (paired with the
        // trust-all SSLContext below). Must be set before the client initialises.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    /** Time budget for opening the TCP/TLS/WebSocket connection to the dongle. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HouseProperties.Inverter props;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient httpClient;

    /** service name -> future awaiting that service's next response */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final StringBuilder inbound = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile String token = "";

    public WiNetWebSocketClient(HouseProperties house) {
        this.props = house.inverter();
        this.httpClient = HttpClient.newBuilder()
                .sslContext(trustAllContext())
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public synchronized boolean isConnected() {
        return webSocket != null && !webSocket.isInputClosed() && !webSocket.isOutputClosed();
    }

    /** Opens the socket and completes the connect→login handshake. */
    public synchronized void connectAndLogin() throws Exception {
        closeQuietly();
        pending.clear();
        inbound.setLength(0);
        token = "";

        log.info("Connecting to WiNet-S at {}", props.wsUri());
        this.webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(props.wsUri()), new Listener())
                .get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        JsonNode connect = call("connect", null);
        this.token = connect.path("result_data").path("token").asText("");

        JsonNode login = call("login", node -> {
            node.put("username", props.username());
            node.put("passwd", props.password());
        });
        String newToken = login.path("result_data").path("token").asText("");
        if (!newToken.isBlank()) this.token = newToken;
        log.info("WiNet-S login ok (uid={}, role={})",
                login.path("result_data").path("uid").asText("?"),
                login.path("result_data").path("role").asText("?"));
    }

    /** Reads the attached device list and returns the first inverter. */
    public DeviceInfo fetchFirstDevice() throws Exception {
        DeviceListResponse resp = callTyped("devicelist", node -> {
            node.put("type", "0");
            node.put("is_check_token", "0");
        }, DeviceListResponse.class);
        if (resp.list().isEmpty()) {
            throw new IllegalStateException("No devices reported by dongle");
        }
        var d = resp.list().get(0);
        return new DeviceInfo(d.devId(), d.devType(), d.devModel(), d.devSn());
    }

    /** Real-time measurement snapshot for a device (the {@code real} service). */
    public RealResponse fetchReal(DeviceInfo dev) throws Exception {
        return callTyped("real", withDevice(dev), RealResponse.class);
    }

    /** DC / MPPT inputs for a device (the {@code direct} service). */
    public DirectResponse fetchDirect(DeviceInfo dev) throws Exception {
        return callTyped("direct", withDevice(dev), DirectResponse.class);
    }

    private java.util.function.Consumer<ObjectNode> withDevice(DeviceInfo dev) {
        return node -> {
            node.put("dev_id", String.valueOf(dev.devId()));
            node.put("dev_type", String.valueOf(dev.devType()));
            node.put("time123456", System.currentTimeMillis());
        };
    }

    /** Sends a request and deserialises its {@code result_data} into {@code type}. */
    private <T> T callTyped(String service, java.util.function.Consumer<ObjectNode> enrich, Class<T> type)
            throws Exception {
        JsonNode env = call(service, enrich);
        return mapper.convertValue(env.path("result_data"), type);
    }

    // ---- transport ---------------------------------------------------------

    /**
     * Sends one request for {@code service} and waits for the matching response.
     * {@code enrich} may add service-specific parameters to the payload.
     */
    private JsonNode call(String service, java.util.function.Consumer<ObjectNode> enrich) throws Exception {
        WebSocket ws = this.webSocket;
        if (ws == null) throw new IllegalStateException("WebSocket not open");

        ObjectNode payload = mapper.createObjectNode();
        payload.put("lang", "en_us");
        payload.put("token", token);
        payload.put("service", service);
        if (enrich != null) enrich.accept(payload);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(service, future);
        ws.sendText(payload.toString(), true);

        JsonNode resp;
        try {
            resp = future.get(props.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(service);
            throw new TimeoutException("No '" + service + "' response within "
                    + props.requestTimeoutMs() + "ms");
        }

        checkResultCode(resp, service);
        return resp;
    }

    /** Throws {@link SessionExpiredException} when the dongle reports code 106. */
    static void checkResultCode(JsonNode resp, String service) {
        int code = resp.path("result_code").asInt(1);
        if (code == 106) { // token stale / session expired
            throw new SessionExpiredException("service '" + service + "' returned 106 (session expired)");
        }
    }

    /** Test seam: register a pending future for {@code service} without sending. */
    CompletableFuture<JsonNode> awaitService(String service) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(service, future);
        return future;
    }

    /** Parses one complete text frame and completes the matching pending request. */
    void handleMessage(String message) {
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (Exception e) {
            log.debug("Ignoring non-JSON frame ({} chars)", message.length());
            return;
        }
        String service = root.path("result_data").path("service").asText(null);
        if (service == null) service = root.path("service").asText(null);
        if (service == null) return;
        CompletableFuture<JsonNode> future = pending.remove(service);
        if (future != null) future.complete(root);
    }

    private void closeQuietly() {
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignore) {
                // best effort
            }
            this.webSocket = null;
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("socket closed")));
        pending.clear();
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket ws) {
            ws.request(Long.MAX_VALUE); // unlimited demand: always deliver frames
        }

        @Override
        public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
            inbound.append(data);
            if (last) {
                String full = inbound.toString();
                inbound.setLength(0);
                try {
                    handleMessage(full);
                } catch (Exception e) {
                    log.warn("Error handling frame", e);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("WebSocket error: {}", error.toString());
            pending.values().forEach(f -> f.completeExceptionally(error));
            pending.clear();
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("WebSocket closed: {} {}", statusCode, reason);
            pending.values().forEach(f -> f.completeExceptionally(
                    new IllegalStateException("closed: " + statusCode)));
            pending.clear();
            return null;
        }
    }

    /** Thrown when the dongle reports a stale session so the poller can re-login. */
    public static class SessionExpiredException extends RuntimeException {
        public SessionExpiredException(String m) { super(m); }
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build trust-all SSLContext", e);
        }
    }
}
