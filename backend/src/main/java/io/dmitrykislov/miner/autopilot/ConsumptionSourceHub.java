package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerReading;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link ConsumptionSource} implementation: a lock-free holder of the latest reading. Source
 * adapters {@code publish()} into it; the engine reads {@code latest()}.
 */
@Component
public class ConsumptionSourceHub implements ConsumptionSource {

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
