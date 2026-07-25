package io.dmitrykislov.miner.inverter.model;

import io.dmitrykislov.miner.util.Rounding;

/**
 * The headline the user asked for: how current solar generation compares to
 * household consumption.
 *
 * <p>{@link #solarPowerKw} is always measured (the inverter's AC active power).
 * House consumption is measured by the Powersensor mains clamp; when no live
 * reading is available the house figure — and therefore the margin — is
 * <em>unavailable</em> (the nullable fields are {@code null} and
 * {@link #consumptionMetered} is {@code false}). There is no assumed baseline.
 */
public record PowerBalance(
        // Measured AC active power the inverter is producing right now, in kW.
        double solarPowerKw,
        // Measured household load in kW; null when the Powersensor isn't reporting.
        Double houseConsumptionKw,
        // Margin = solar - house (kW). Positive ⇒ surplus exported; negative ⇒
        // deficit imported. null when house consumption is unknown.
        Double netSurplusKw,
        // true when solar >= house (self-sufficient / exporting); null when unknown.
        Boolean coveringLoad,
        // Share of house load met by solar, 0..100 (capped). null when unknown.
        Double solarCoveragePct,
        // true when houseConsumptionKw is a real measured reading (Powersensor
        // clamp); false when there is no live reading. Drives the UI badge.
        boolean consumptionMetered) {

    /** Margin from a live Powersensor reading. */
    public static PowerBalance metered(double solarKw, double houseKw) {
        double net = Rounding.toPlaces(solarKw - houseKw, 3);
        double coverage = houseKw <= 0 ? 100.0 : Math.min(100.0, Rounding.toPlaces(solarKw / houseKw * 100.0, 3));
        return new PowerBalance(Rounding.toPlaces(solarKw, 3), Rounding.toPlaces(houseKw, 3),
                net, solarKw >= houseKw, coverage, true);
    }

    /** No live meter reading — house consumption and the margin are unavailable. */
    public static PowerBalance unmetered(double solarKw) {
        return new PowerBalance(Rounding.toPlaces(solarKw, 3), null, null, null, null, false);
    }
}
