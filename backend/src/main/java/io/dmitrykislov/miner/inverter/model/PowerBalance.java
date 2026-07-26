package io.dmitrykislov.miner.inverter.model;

import io.dmitrykislov.miner.util.Rounding;

/**
 * How current solar generation compares to household consumption.
 *
 * <p>{@link #solarPowerKw} is measured by the inverter; {@link #houseConsumptionKw}
 * is measured directly by Solar Analytics (whole-home CTs). The rest follows:
 * <ul>
 *   <li>{@code netSurplusKw = solar − house}  (the exportable surplus)</li>
 *   <li>{@code gridPowerKw   = house − solar}  (net grid: + importing, − exporting; derived)</li>
 * </ul>
 *
 * <p>When no fresh consumption reading is available, house/grid/margin are
 * <em>unavailable</em> (nullable fields {@code null}, {@link #consumptionMetered} false).
 */
public record PowerBalance(
        // Measured AC active power the inverter is producing right now, in kW.
        double solarPowerKw,
        // Net grid power (kW): + importing, − exporting. Derived (house − solar). null if unmetered.
        Double gridPowerKw,
        // Measured whole-home consumption (kW). null if unmetered.
        Double houseConsumptionKw,
        // Surplus margin = solar − house (kW). Positive = exporting surplus; negative =
        // drawing from the grid. null if unmetered.
        Double netSurplusKw,
        // true when the home is fully solar-covered (surplus ≥ 0). null if unknown.
        Boolean coveringLoad,
        // Share of house load met by solar, 0..100 (capped). null if unknown.
        Double solarCoveragePct,
        // true when a live consumption reading backs the figures. Drives the UI badge.
        boolean consumptionMetered) {

    /**
     * Build from measured solar and measured house consumption.
     *
     * @param solarKw measured inverter AC power (kW)
     * @param houseKw measured whole-home consumption (kW, ≥ 0)
     */
    public static PowerBalance metered(double solarKw, double houseKw) {
        double surplus = solarKw - houseKw;   // exportable surplus
        double grid = houseKw - solarKw;      // net grid (+ import / − export)
        double coverage = houseKw <= 0 ? 100.0
                : Math.min(100.0, Rounding.toPlaces(solarKw / houseKw * 100.0, 3));
        return new PowerBalance(
                Rounding.toPlaces(solarKw, 3),
                Rounding.toPlaces(grid, 3),
                Rounding.toPlaces(houseKw, 3),
                Rounding.toPlaces(surplus, 3),
                surplus >= 0,
                coverage,
                true);
    }

    /** No live consumption reading — grid, house, and the margin are unavailable. */
    public static PowerBalance unmetered(double solarKw) {
        return new PowerBalance(Rounding.toPlaces(solarKw, 3), null, null, null, null, null, false);
    }
}
