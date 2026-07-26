package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.util.Rounding;

import java.time.Instant;

/**
 * A whole-home consumption reading from Solar Analytics (their CT hardware).
 * {@code powerW}/{@code powerKw} are the measured house load and are always ≥ 0.
 *
 * @param powerW        measured house consumption, watts
 * @param powerKw       same value in kilowatts
 * @param mainsVoltageV mains voltage if reported (nullable — the live feed omits it)
 * @param sourceMac     the Solar Analytics site id this reading came from
 * @param metered       true for a real measured reading
 * @param timestamp     when this reading was received (server clock)
 */
public record HousePower(
        double powerW,
        double powerKw,
        Double mainsVoltageV,
        String sourceMac,
        boolean metered,
        Instant timestamp) {

    public static HousePower measured(double watts, Double voltage, String source, Instant ts) {
        return new HousePower(Rounding.toPlaces(watts, 1), Rounding.toPlaces(watts / 1000.0, 3),
                voltage, source, true, ts);
    }
}
