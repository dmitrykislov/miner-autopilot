package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.util.Rounding;

import java.time.Instant;

/**
 * A live reading from the Powersensor mains clamp. The clamp sits on the grid
 * feed, so this is <b>net grid power</b>: {@code powerW}/{@code powerKw} are
 * <b>positive when importing</b> from the grid and <b>negative when exporting</b>.
 * House consumption is not measured directly — it is derived as {@code solar + grid}
 * (see {@link io.dmitrykislov.miner.inverter.model.PowerBalance}).
 *
 * @param powerW        net grid power, watts (+ import / − export)
 * @param powerKw       same value in kilowatts
 * @param mainsVoltageV latest mains RMS voltage from the gateway plug (nullable —
 *                      the clamp itself reports no voltage)
 * @param sourceMac     MAC of the reporting clamp
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

    public static HousePower measured(double watts, Double voltage, String mac, Instant ts) {
        return new HousePower(Rounding.toPlaces(watts, 1), Rounding.toPlaces(watts / 1000.0, 3),
                voltage, mac, true, ts);
    }
}
