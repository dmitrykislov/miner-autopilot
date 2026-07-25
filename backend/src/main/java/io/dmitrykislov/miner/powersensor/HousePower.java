package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.util.Rounding;

import java.time.Instant;

/**
 * A live whole-home power reading derived from the Powersensor mains clamp.
 *
 * @param powerW        measured whole-home power draw, in watts
 * @param powerKw       same value in kilowatts (for the margin/UI)
 * @param mainsVoltageV latest mains RMS voltage from the gateway plug (nullable —
 *                      the clamp itself reports no voltage)
 * @param sourceMac     MAC of the reporting clamp
 * @param metered       true for a real measured reading; false for the
 *                      "no data yet / sensor disabled" placeholder
 * @param timestamp     when this reading was received (server clock)
 */
public record HousePower(
        double powerW,
        double powerKw,
        Double mainsVoltageV,
        String sourceMac,
        boolean metered,
        Instant timestamp) {

    public static HousePower measured(double watts, Double voltage, String mac, Instant ts) {
        return new HousePower(Rounding.toPlaces(watts, 1), Rounding.toPlaces(watts / 1000.0, 3),
                voltage, mac, true, ts);
    }
}
