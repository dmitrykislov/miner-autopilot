package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The assumed household consumption baseline used for the solar-vs-house margin
 * <em>when no live meter reading is available</em>. Seeded from
 * {@code house.power-sensor.assumed-load-kw} and adjustable at runtime from the UI.
 *
 * <p>When the Powersensor is reporting, {@link HouseConsumptionState} supplies the
 * real measured value instead and this baseline is only a fallback.
 */
@Component
public class HouseLoadState {

    private final AtomicReference<Double> houseLoadKw;

    public HouseLoadState(HouseProperties props) {
        this.houseLoadKw = new AtomicReference<>(props.powerSensor().assumedLoadKw());
    }

    public double get() {
        return houseLoadKw.get();
    }

    public void set(double kw) {
        houseLoadKw.set(Math.max(0.0, kw));
    }
}
