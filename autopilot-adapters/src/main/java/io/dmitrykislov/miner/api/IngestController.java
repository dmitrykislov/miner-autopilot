package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Optional HTTP ingest: push solar / whole-home-consumption readings into the engine's ports from an
 * <b>external</b> source in any language or process — an alternative to writing an in-JVM adapter.
 *
 * <p>Off by default; enable with {@code house.ingest.enabled=true} (env {@code INGEST_ENABLED=true}).
 * Protected by the same bearer token as the rest of {@code /api/**}. Readings are stamped
 * server-side (as received) to avoid trusting an external clock. Post a reading whenever you have a
 * live value; call {@code /clear} when you don't (e.g. your source went down) so the engine treats
 * the surplus as unknown and safely stops the miner rather than acting on a stale value.
 *
 * <pre>
 *   POST /api/ingest/solar?watts=4200          # solar generation now
 *   POST /api/ingest/consumption?watts=900     # whole-home consumption now
 *   POST /api/ingest/solar/clear               # solar reading no longer available
 *   POST /api/ingest/consumption/clear
 * </pre>
 *
 * Typically paired with turning the matching built-in off ({@code INVERTER_ENABLED=false} /
 * {@code SOLARANALYTICS_ENABLED=false}) so the two don't both feed the same port.
 */
@RestController
@RequestMapping("/api/ingest")
@CrossOrigin
@ConditionalOnProperty(name = "house.ingest.enabled", havingValue = "true")
public class IngestController {

    private final SolarSource solar;
    private final ConsumptionSource consumption;

    public IngestController(SolarSource solar, ConsumptionSource consumption) {
        this.solar = solar;
        this.consumption = consumption;
    }

    @PostMapping("/solar")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void solar(@RequestParam double watts) {
        solar.publish(new PowerReading(Instant.now(), watts));
    }

    @PostMapping("/solar/clear")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void clearSolar() {
        solar.clear();
    }

    @PostMapping("/consumption")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void consumption(@RequestParam double watts) {
        consumption.publish(new PowerReading(Instant.now(), watts));
    }

    @PostMapping("/consumption/clear")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void clearConsumption() {
        consumption.clear();
    }
}
