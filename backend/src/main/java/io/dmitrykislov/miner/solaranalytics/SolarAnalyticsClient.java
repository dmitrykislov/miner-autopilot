package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class SolarAnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(SolarAnalyticsClient.class);

    private final HouseProperties.SolarAnalytics cfg;
    private final HouseConsumptionState consumption;
    private final HousePowerStreamService stream;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String authHeader;
    private volatile String siteId;

    public SolarAnalyticsClient(HouseProperties props, HouseConsumptionState consumption,
                                HousePowerStreamService stream) {
        this.cfg = props.solarAnalytics();
        this.consumption = consumption;
        this.stream = stream;
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
        try {
            String site = resolveSiteId();
            if (site == null || site.isBlank()) {
                log.warn("Solar Analytics: no active site available");
                return;
            }
            Double consumedW = latestConsumedWatts(getJson("/live_site_data?site_id=" + site + "&last_six=true"));
            if (consumedW == null) {
                log.debug("Solar Analytics: no consumption in live data for site {}", site);
                return;
            }
            HousePower reading = HousePower.measured(consumedW, null, site, Instant.now());
            consumption.update(reading);
            stream.publish(reading); // push to the UI immediately
            log.debug("House consumption {} W (site {})", reading.powerW(), site);
        } catch (Exception e) {
            log.warn("Solar Analytics poll failed: {}", e.toString());
        }
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
