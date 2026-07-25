package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;

/**
 * Polls the Tapo plug for its on/off + energy status, publishes each snapshot to
 * SSE subscribers, and performs on/off/toggle commands (refreshing status
 * immediately after so the UI reflects the change without waiting for a poll).
 */
@Service
public class PlugService {

    private static final Logger log = LoggerFactory.getLogger(PlugService.class);

    private final PlugTransport client;
    private final PlugStreamService stream;
    private final HouseProperties.Plug cfg;

    public PlugService(PlugTransport client, PlugStreamService stream, HouseProperties props) {
        this.client = client;
        this.stream = stream;
        this.cfg = props.plug();
    }

    @Scheduled(fixedDelayString = "${house.plug.poll-interval-ms:10000}",
               initialDelayString = "2000")
    public void poll() {
        if (!cfg.enabled()) return;
        refresh();
    }

    /** Fetches current status and publishes it; returns what was published. */
    public PlugStatus refresh() {
        Instant now = Instant.now();
        if (!cfg.enabled()) {
            return publish(PlugStatus.offline(displayName(null), now, "plug integration disabled"));
        }
        if (!cfg.hasCredentials()) {
            return publish(PlugStatus.offline(displayName(null), now, "no Tapo account credentials configured"));
        }
        try {
            JsonNode info = client.getDeviceInfo();
            Double power = null, today = null;
            try {
                JsonNode e = client.getEnergyUsage();
                if (e.hasNonNull("current_power")) power = e.path("current_power").asDouble() / 1000.0; // mW→W
                if (e.hasNonNull("today_energy")) today = e.path("today_energy").asDouble();            // Wh
            } catch (Exception ignore) {
                // energy monitoring optional; ignore if unavailable
            }
            PlugStatus s = new PlugStatus(
                    true,
                    info.path("device_on").asBoolean(false),
                    displayName(decodeNickname(info)),
                    info.path("model").asText(null),
                    power, today, now, null);
            return publish(s);
        } catch (PlugTransport.AuthException e) {
            log.warn("Tapo auth failed: {}", e.getMessage());
            return publish(PlugStatus.offline(displayName(null), now, "authentication failed — check credentials"));
        } catch (Exception e) {
            log.warn("Tapo poll failed: {}", e.toString());
            return publish(PlugStatus.offline(displayName(null), now, e.getMessage()));
        }
    }

    /** Sets the relay and immediately refreshes status. */
    public PlugStatus setOn(boolean on) {
        try {
            client.setOn(on);
        } catch (Exception e) {
            log.warn("Tapo setOn({}) failed: {}", on, e.toString());
            return publish(PlugStatus.offline(displayName(null), Instant.now(), e.getMessage()));
        }
        return refresh();
    }

    /** Flips the relay based on the last known state. */
    public PlugStatus toggle() {
        PlugStatus cur = stream.latest();
        boolean target = !(cur != null && cur.on());
        return setOn(target);
    }

    private PlugStatus publish(PlugStatus s) {
        stream.publish(s);
        return s;
    }

    private String displayName(String nickname) {
        if (cfg.name() != null && !cfg.name().isBlank()) return cfg.name();
        return (nickname != null && !nickname.isBlank()) ? nickname : "Tapo P110";
    }

    private static String decodeNickname(JsonNode info) {
        String b64 = info.path("nickname").asText(null);
        if (b64 == null || b64.isBlank()) return null;
        try {
            return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
