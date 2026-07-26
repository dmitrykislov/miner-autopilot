package io.dmitrykislov.miner.inverter.model;

import io.dmitrykislov.miner.util.Rounding;

/**
 * How current solar generation compares to household consumption.
 *
 * <p>The Powersensor clamp sits on the <b>mains</b> and measures <b>net grid
 * power</b> ({@link #gridPowerKw}): positive = importing from the grid, negative =
 * exporting to it. It does <em>not</em> measure house load directly. Combined with
 * the inverter's measured {@link #solarPowerKw}, the rest follows from the energy
 * balance {@code solar = house + export}:
 *
 * <ul>
 *   <li>{@code houseConsumptionKw = solar + grid}  (grid is signed; export is negative)</li>
 *   <li>{@code netSurplusKw       = solar − house = −grid}  (the exportable surplus)</li>
 * </ul>
 *
 * <p>When the Powersensor isn't reporting, grid/house/margin are <em>unavailable</em>
 * (the nullable fields are {@code null}, {@link #consumptionMetered} is {@code false}).
 * There is no assumed baseline.
 */
public record PowerBalance(
        // Measured AC active power the inverter is producing right now, in kW.
        double solarPowerKw,
        // Net grid power (kW): + = importing from grid, − = exporting. null if unmetered.
        Double gridPowerKw,
        // Household consumption (kW) = solar + grid. null if unmetered.
        Double houseConsumptionKw,
        // Surplus margin = solar − house = −grid (kW). Positive = exporting surplus;
        // negative = drawing from the grid. null if unmetered.
        Double netSurplusKw,
        // true when the home is fully solar-covered (surplus ≥ 0). null if unknown.
        Boolean coveringLoad,
        // Share of house load met by solar, 0..100 (capped). null if unknown.
        Double solarCoveragePct,
        // true when a live Powersensor reading backs the figures. Drives the UI badge.
        boolean consumptionMetered) {

    /**
     * Build from measured solar and the signed net-grid clamp reading.
     *
     * @param solarKw   measured inverter AC power (kW)
     * @param gridNetKw net grid power (kW): + importing, − exporting
     */
    public static PowerBalance metered(double solarKw, double gridNetKw) {
        double house = Math.max(0.0, solarKw + gridNetKw);   // energy balance; guard tiny negatives
        double surplus = -gridNetKw;                          // = solar − house (the exportable surplus)
        double coverage = house <= 0 ? 100.0
                : Math.min(100.0, Rounding.toPlaces(solarKw / house * 100.0, 3));
        return new PowerBalance(
                Rounding.toPlaces(solarKw, 3),
                Rounding.toPlaces(gridNetKw, 3),
                Rounding.toPlaces(house, 3),
                Rounding.toPlaces(surplus, 3),
                surplus >= 0,
                coverage,
                true);
    }

    /** No live meter reading — grid, house, and the margin are unavailable. */
    public static PowerBalance unmetered(double solarKw) {
        return new PowerBalance(Rounding.toPlaces(solarKw, 3), null, null, null, null, null, false);
    }
}
