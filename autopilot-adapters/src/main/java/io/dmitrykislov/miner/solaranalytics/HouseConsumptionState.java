package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the most recent whole-home consumption reading from Solar Analytics and
 * answers whether it is still "fresh" enough to trust. When stale (or never
 * received), the solar-vs-house margin is unavailable. {@link #measuredKw()}
 * returns the measured house consumption in kW (always ≥ 0).
 */
@Component
public class HouseConsumptionState {

    private final Duration staleAfter;
    private final AtomicReference<HousePower> latest = new AtomicReference<>();

    public HouseConsumptionState(HouseProperties props) {
        this.staleAfter = Duration.ofSeconds(props.solarAnalytics().staleAfterSeconds());
    }

    public void update(HousePower reading) {
        latest.set(reading);
    }

    public HousePower latest() {
        return latest.get();
    }

    private boolean isFresh(Instant now) {
        HousePower p = latest.get();
        return p != null && p.metered()
                && Duration.between(p.timestamp(), now).compareTo(staleAfter) <= 0;
    }

    /** Measured house consumption in kW if a fresh reading exists, otherwise empty. */
    public Optional<Double> measuredKw() {
        return isFresh(Instant.now()) ? Optional.of(latest.get().powerKw()) : Optional.empty();
    }
}
