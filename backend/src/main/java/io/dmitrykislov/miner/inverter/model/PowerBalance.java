package io.dmitrykislov.miner.inverter.model;

/**
 * The headline the user asked for: how current solar generation compares to
 * household consumption.
 *
 * <p>Important: the SG10RS has no energy meter, so {@link #houseConsumptionKw}
 * is NOT measured — it is an assumed/configured baseline (overridable from the
 * UI). {@link #solarPowerKw} IS measured (the inverter's AC active power).
 */
public record PowerBalance(
        // Measured AC active power the inverter is producing right now, in kW.
        double solarPowerKw,
        // Assumed household load in kW (configured baseline — NOT metered).
        double houseConsumptionKw,
        // Margin = solar - house. Positive ⇒ surplus exported to grid;
        // negative ⇒ deficit drawn from the grid. Unit: kW.
        double netSurplusKw,
        // true when solar >= house (self-sufficient / exporting).
        boolean coveringLoad,
        // Share of house load met by solar, 0..100 (capped at 100). Unit: %.
        double solarCoveragePct,
        // true when houseConsumptionKw is a real measured reading (Powersensor
        // clamp); false when it is the assumed baseline. Drives the UI badge.
        boolean consumptionMetered) {

    /** Margin from an assumed (non-metered) house load. */
    public static PowerBalance of(double solarKw, double houseKw) {
        return of(solarKw, houseKw, false);
    }

    /** Margin where {@code metered} says whether houseKw is a real meter reading. */
    public static PowerBalance of(double solarKw, double houseKw, boolean metered) {
        double net = round(solarKw - houseKw);
        double coverage = houseKw <= 0 ? 100.0 : Math.min(100.0, round(solarKw / houseKw * 100.0));
        return new PowerBalance(
                round(solarKw),
                round(houseKw),
                net,
                solarKw >= houseKw,
                coverage,
                metered);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
