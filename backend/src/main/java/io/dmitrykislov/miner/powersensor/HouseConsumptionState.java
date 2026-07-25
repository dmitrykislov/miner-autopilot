package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the most recent measured whole-home consumption from the Powersensor
 * clamp, and answers whether it is still "fresh" enough to trust. When stale (or
 * never received), the solar-vs-house margin falls back to the assumed baseline.
 */
@Component
public class HouseConsumptionState {

    private final Duration staleAfter;
    private final AtomicReference<HousePower> latest = new AtomicReference<>();

    public HouseConsumptionState(HouseProperties props) {
        this.staleAfter = Duration.ofSeconds(props.powerSensor().staleAfterSeconds());
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

    public boolean isMetered() {
        return isFresh(Instant.now());
    }

    /** Measured consumption in kW if a fresh reading exists, otherwise empty. */
    public Optional<Double> measuredKw() {
        return isFresh(Instant.now()) ? Optional.of(latest.get().powerKw()) : Optional.empty();
    }
}
