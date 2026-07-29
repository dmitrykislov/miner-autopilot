package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link SolarSource} implementation: a lock-free holder of the latest reading. Source
 * adapters {@code publish()} into it; the engine reads {@code latest()}. Keeping only the latest is
 * all the engine needs — {@link EnergySampler} samples on its own cadence and {@link EnergyAverages}
 * does the time-windowing.
 */
@Component
public class SolarSourceHub implements SolarSource {

    private final AtomicReference<PowerReading> latest = new AtomicReference<>();

    @Override
    public void publish(PowerReading reading) {
        latest.set(reading);
    }

    @Override
    public void clear() {
        latest.set(null);
    }

    @Override
    public Optional<PowerReading> latest() {
        return Optional.ofNullable(latest.get());
    }
}
