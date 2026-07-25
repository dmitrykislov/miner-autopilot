package io.dmitrykislov.miner.powersensor;

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
        return new HousePower(round(watts), round(watts / 1000.0, 3), voltage, mac, true, ts);
    }

    public static HousePower unavailable(Instant ts) {
        return new HousePower(0.0, 0.0, null, null, false, ts);
    }

    private static double round(double v) { return round(v, 1); }
    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
