package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Client for the Solar Analytics cloud API. Every {@code house.solar-analytics.poll-interval-ms}
 * it reads real-time whole-home power from {@code /live_site_data} and publishes the
 * {@code consumed} figure as the house-consumption source (feeding
 * {@link HouseConsumptionState} and {@link HousePowerStreamService}).
 *
 * <p>Authenticates with the account email + password over HTTP Basic — no app keys.
 * If no {@code site-id} is configured it auto-selects the first active site.
 *
 * <p>The SG10RS inverter exposes no whole-home energy meter, so Solar Analytics
 * (their own CT hardware) is the source of truth for house consumption.
 *
 * <p>To avoid pointless cloud calls when no surplus is possible, the poll is gated
 * on live solar generation: it only fetches consumption while the inverter is
 * generating more than {@code house.solar-analytics.min-solar-w}. Below that the
 * margin can't support the miner anyway, and the reading simply goes stale (which
 * the autopilot treats as an unknown margin → safe stop). The gate reads the latest
 * inverter snapshot (a lock-free in-memory value), so it never blocks the poll.
 */
@Component
// The built-in whole-home consumption adapter. Turn it off (house.solar-analytics.enabled=false)
// to feed the ConsumptionSource port from a custom adapter instead. Default: on.
@ConditionalOnProperty(name = "house.solar-analytics.enabled", havingValue = "true", matchIfMissing = true)
public class SolarAnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(SolarAnalyticsClient.class);

    private final HouseProperties.SolarAnalytics cfg;
    private final HouseConsumptionState consumption;
    private final HousePowerStreamService stream;
    private final InverterStreamService inverter;
    private final ConsumptionSource consumptionSource;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String authHeader;
    private volatile String siteId;

    // Outage handling: warn about a persistent failure loudly once (then quietly), and after this
    // many consecutive failures mark consumption UNAVAILABLE so the surplus goes unknown → the
    // autopilot safely stops, instead of acting on the last reading until it ages out.
    private static final int FAILURES_BEFORE_UNAVAILABLE = 3;
    private volatile boolean loggedFailure = false;
    private volatile int consecutiveFailures = 0;

    public SolarAnalyticsClient(HouseProperties props, HouseConsumptionState consumption,
                                HousePowerStreamService stream, InverterStreamService inverter,
                                ConsumptionSource consumptionSource) {
        this.cfg = props.solarAnalytics();
        this.consumption = consumption;
        this.stream = stream;
        this.inverter = inverter;
        this.consumptionSource = consumptionSource;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (cfg.user() + ":" + cfg.password()).getBytes(StandardCharsets.UTF_8));
        this.siteId = cfg.siteId();
    }

    @Scheduled(fixedDelayString = "${house.solar-analytics.poll-interval-ms:15000}", initialDelayString = "3000")
    public void poll() {
        if (!cfg.enabled()) return;
        if (!cfg.hasCredentials()) {
            log.debug("Solar Analytics: no credentials configured — skipping");
            return;
        }
        // Gate: only spend an API call when there is meaningful solar coming in.
        double solarW = currentSolarWatts();
        if (solarW <= cfg.minSolarWatts()) {
            log.debug("Solar Analytics: solar {}W ≤ {}W — no usable surplus, skipping consumption fetch",
                    Math.round(solarW), cfg.minSolarWatts());
            // Mark consumption UNAVAILABLE rather than leaving the last (pre-dip) reading to go
            // stale slowly: a running miner's draw may not be in that frozen reading, so keeping
            // it would let the margin look better than reality and hold the miner importing for
            // up to the stale window. Unavailable → margin unknown → autopilot safely stops.
            HousePower none = HousePower.unavailable(Instant.now());
            consumption.update(none);
            stream.publish(none);
            consumptionSource.clear(); // engine's port: no live reading → surplus unknown → safe stop
            return;
        }
        try {
            String site = resolveSiteId();
            if (site == null || site.isBlank()) {
                onPollFailure("no active site available", false);
                return;
            }
            Double consumedW = latestConsumedWatts(getJson("/live_site_data?site_id=" + site + "&last_six=true"));
            if (consumedW == null) {
                onPollFailure("no consumption in live data for site " + site, false);
                return;
            }
            Instant readingAt = Instant.now();
            HousePower reading = HousePower.measured(consumedW, null, site, readingAt);
            consumption.update(reading);
            stream.publish(reading); // push to the UI immediately
            consumptionSource.publish(new PowerReading(readingAt, consumedW)); // feed the engine's port
            consecutiveFailures = 0;   // healthy poll → reset the outage counter and re-arm the warning
            loggedFailure = false;
            log.debug("House consumption {} W (site {})", reading.powerW(), site);
        } catch (Exception e) {
            onPollFailure(e.toString(), isAuthError(e));
        }
    }

    /**
     * A poll produced no fresh reading (transport error, 200-with-no-data, or no active site). Warn
     * loudly once, then stay quiet while it persists so a sustained outage can't flood the log.
     *
     * <p>After {@link #FAILURES_BEFORE_UNAVAILABLE} consecutive such polls, mark consumption
     * UNAVAILABLE (clear the port). The autopilot is already safe without this — it ignores a
     * consumption reading older than {@code ~4× the inverter poll} — but consumers that judge the
     * port's {@code latest()} by <em>receipt</em> freshness would otherwise keep serving the stale
     * value: the {@code /api/power} dashboard feed is kept "live" by the SSE keep-alive heartbeat,
     * so without clearing it would show a frozen house figure as current. Clearing makes consumption
     * read unavailable everywhere. A one-off blip is ridden out; the next good poll resets.
     */
    private void onPollFailure(String detail, boolean authError) {
        consecutiveFailures++;
        if (loggedFailure) {
            log.debug("Solar Analytics still failing (attempt {}): {}", consecutiveFailures, detail);
        } else {
            if (authError) {
                log.warn("Solar Analytics auth failed — check SOLARANALYTICS_USER / SOLARANALYTICS_PASSWORD: {}",
                        detail);
            } else {
                log.warn("Solar Analytics poll failed: {}", detail);
            }
            loggedFailure = true;
        }
        if (consecutiveFailures >= FAILURES_BEFORE_UNAVAILABLE) {
            HousePower none = HousePower.unavailable(Instant.now());
            consumption.update(none);
            stream.publish(none);
            consumptionSource.clear();
        }
    }

    /** A 401/403 means the credentials are wrong — retrying won't fix it, so flag it distinctly. */
    private static boolean isAuthError(Throwable e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 401") || m.contains("HTTP 403"));
    }

    /** Live solar generation (watts) from the latest inverter snapshot; 0 if none/offline. */
    private double currentSolarWatts() {
        InverterSnapshot snap = inverter.latest();
        if (snap == null || !snap.online() || snap.powerBalance() == null) return 0.0;
        return snap.powerBalance().solarPowerKw() * 1000.0;
    }

    /** Newest {@code consumed} (watts) from a {@code /live_site_data} response, or null. */
    static Double latestConsumedWatts(JsonNode liveResp) {
        JsonNode data = liveResp.path("data");
        if (!data.isArray() || data.isEmpty()) return null;
        JsonNode latest = data.get(data.size() - 1);
        return latest.hasNonNull("consumed") ? latest.path("consumed").asDouble() : null;
    }

    /** The configured site id, or (once) the first active site from {@code /site_list}. */
    private String resolveSiteId() throws Exception {
        if (siteId != null && !siteId.isBlank()) return siteId;
        for (JsonNode s : getJson("/site_list").path("data")) {
            if (!s.path("site_inactive").asBoolean(false)) {
                siteId = s.path("site_id").asText();
                log.info("Solar Analytics: auto-selected active site {}", siteId);
                return siteId;
            }
        }
        return null;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.host() + path))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs()))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " from " + path);
        }
        return mapper.readTree(resp.body());
    }
}
