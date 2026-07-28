package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.history.TelemetrySample;
import io.dmitrykislov.miner.history.TelemetryStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Warms {@link EnergyAverages} from persisted history at startup so the autopilot isn't blind for a
 * whole long-window (~15 min) after a controller restart.
 *
 * <p>Without this, the rolling windows start empty: after a reboot the governor sees
 * {@code shortSurplus}/{@code longSurplus} as under-covered and holds ("insufficient recent data")
 * until enough <em>fresh</em> samples accumulate — up to the long window. By replaying the last
 * long-window of stored {@link TelemetrySample}s (solar, consumption and the co-sampled miner draw,
 * exactly as {@link EnergySampler} feeds them live), the windows are pre-populated: as soon as one
 * fresh sample arrives post-reboot, both averages are covered <em>and</em> fresh, so the autopilot
 * resumes correct decisions in one poll interval instead of ~15 minutes.
 *
 * <p>Only real persisted data is replayed — nothing is fabricated. If history is empty/disabled it
 * is a no-op.
 */
@Component
public class EnergyWarmup {

    private static final Logger log = LoggerFactory.getLogger(EnergyWarmup.class);

    private final TelemetryStore store;
    private final EnergyAverages energy;
    private final Duration longWindow;

    public EnergyWarmup(TelemetryStore store, EnergyAverages energy, HouseProperties props) {
        this.store = store;
        this.energy = energy;
        this.longWindow = Duration.ofMillis(props.autopilot().longWindowMs());
    }

    @PostConstruct
    public void warmOnStartup() {
        warm(Instant.now());
    }

    /** Replay stored samples from the last {@code longWindow} into the averaging windows. */
    void warm(Instant now) {
        try {
            List<TelemetrySample> recent = store.samplesSince(now.minus(longWindow));
            int replayed = 0;
            for (TelemetrySample s : recent) {
                if (s.at() == null) continue;
                if (s.solarW() != null) energy.recordSolar(s.at(), s.solarW());
                if (s.consumptionW() != null) {
                    energy.recordConsumption(s.at(), s.consumptionW());
                    // Co-sample the miner draw exactly as the live sampler does (0 when not mining),
                    // so the miner-independent surplus average is consistent.
                    energy.recordMinerDraw(s.at(), s.minerDrawW() != null ? s.minerDrawW() : 0.0);
                    replayed++;
                }
            }
            if (replayed > 0) {
                log.info("autopilot: warmed energy windows from {} persisted samples (last {})",
                        replayed, longWindow);
            }
        } catch (Exception e) {
            // Warm-up is best-effort — a failure must never stop the app from booting.
            log.debug("autopilot: energy warm-up skipped: {}", e.toString());
        }
    }
}
