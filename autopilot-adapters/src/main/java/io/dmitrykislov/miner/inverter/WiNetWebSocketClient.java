package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.inverter.dto.DeviceListResponse;
import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.dto.WiNetEnvelope;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import jakarta.annotation.PreDestroy;
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

    /** Guard against a single peer growing the frame buffer without limit via endless continuations. */
    private static final int MAX_FRAME_CHARS = 1_000_000;

    /** service name -> future awaiting that service's next response */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile String token = "";

    /**
     * Transport session counter, bumped on every (re)connect. Frames delivered by a previous
     * session's listener are ignored — see {@link #handleMessage(String, int)}.
     */
    private volatile int session;

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

    /**
     * Close the socket and release any in-flight requests on shutdown, so a redeploy/restart (Spring
     * context close → SIGTERM) doesn't leave a half-open WebSocket to the dongle or callers blocked
     * on a pending future. Synchronized against {@link #connectAndLogin()} so it can't race a
     * concurrent reconnect on the poller thread.
     */
    @PreDestroy
    public synchronized void shutdown() {
        closeQuietly();
    }

    /** Opens the socket and completes the connect→login handshake. */
    public synchronized void connectAndLogin() throws Exception {
        closeQuietly();
        pending.clear();
        token = "";
        // Bump the session BEFORE building the new socket, so the outgoing listener is already
        // superseded and anything it still delivers is ignored.
        int mySession = nextSession();

        log.info("Connecting to WiNet-S at {}", props.wsUri());
        this.webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(props.wsUri()), new Listener(mySession))
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

    /** Throws {@link SessionExpiredException} when the dongle reports a session-expired code. */
    static void checkResultCode(JsonNode resp, String service) {
        int code = resp.path("result_code").asInt(WiNetEnvelope.SUCCESS);
        if (code == WiNetEnvelope.SESSION_EXPIRED) { // token stale / session expired
            throw new SessionExpiredException(
                    "service '" + service + "' returned " + WiNetEnvelope.SESSION_EXPIRED + " (session expired)");
        }
    }

    /** Test seam: register a pending future for {@code service} without sending. */
    CompletableFuture<JsonNode> awaitService(String service) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(service, future);
        return future;
    }

    /**
     * Start a new transport session and return its id. Frames arriving from any earlier session are
     * ignored from this point on. Package-private so tests can simulate a reconnect.
     */
    synchronized int nextSession() {
        return ++session;
    }

    /** Parses one complete text frame and completes the matching pending request (current session). */
    void handleMessage(String message) {
        handleMessage(message, session);
    }

    /**
     * As {@link #handleMessage(String)}, but attributed to the session that received the frame.
     *
     * <p>A frame from a superseded session is discarded. {@code sendClose} only *starts* a close
     * handshake, so after a reconnect the old socket's reader thread can still deliver frames, and its
     * listener shares this object's {@code pending} map. Without this guard a late {@code real}
     * response from the dead socket could satisfy the new session's request — publishing stale inverter
     * data as a live reading, straight into the autopilot's surplus calculation.
     */
    void handleMessage(String message, int session) {
        if (session != this.session) {
            log.debug("Ignoring frame from superseded WiNet session {} (current {})", session, this.session);
            return;
        }
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

    /**
     * Release the current socket and fail anything waiting on it.
     *
     * <p>{@code sendClose} is only a courtesy: it starts a close handshake and the connection is not
     * released until the peer answers with its own close frame. A hung dongle — the very reason we are
     * usually reconnecting — never answers, so relying on it alone leaked a TLS connection per
     * reconnect attempt. {@code abort()} closes the channel immediately, so the socket is gone whether
     * or not the peer cooperates. Sending the close frame first is still worth doing for a healthy
     * peer; aborting straight after may cut it short, which is an acceptable trade for a guaranteed
     * release.
     */
    private void closeQuietly() {
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignore) {
                // best effort
            }
            try {
                ws.abort(); // force-release the TCP/TLS connection; safe to call after sendClose
            } catch (Exception ignore) {
                // best effort
            }
            this.webSocket = null;
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("socket closed")));
        pending.clear();
    }

    /**
     * Per-socket listener. Each one owns its <b>own</b> frame buffer and remembers which session it
     * belongs to, so a superseded socket that is still delivering frames cannot corrupt the live
     * session's frame assembly or complete its requests.
     */
    private final class Listener implements WebSocket.Listener {

        private final int mySession;
        /** Assembles a multi-frame message. Per-listener, so two sockets can never interleave text. */
        private final StringBuilder inbound = new StringBuilder();

        Listener(int mySession) {
            this.mySession = mySession;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(Long.MAX_VALUE); // unlimited demand: always deliver frames
        }

        @Override
        public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (mySession != session) return null; // superseded socket — ignore whatever it sends
            if (inbound.length() + data.length() > MAX_FRAME_CHARS) {
                // A peer sending endless continuation frames would otherwise grow this without limit.
                log.warn("Discarding oversized WiNet frame (> {} chars)", MAX_FRAME_CHARS);
                inbound.setLength(0);
                return null;
            }
            inbound.append(data);
            if (last) {
                String full = inbound.toString();
                inbound.setLength(0);
                try {
                    handleMessage(full, mySession);
                } catch (Exception e) {
                    log.warn("Error handling frame", e);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            // Without the session guard, a dead socket's error would fail the NEW session's in-flight
            // requests and clear its pending map.
            if (mySession != session) {
                log.debug("Ignoring error from superseded WiNet session: {}", error.toString());
                return;
            }
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
