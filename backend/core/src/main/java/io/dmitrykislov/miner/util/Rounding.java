package io.dmitrykislov.miner.util;

/** Small numeric helpers shared across the mappers/DTOs. */
public final class Rounding {

    private Rounding() {}

    /** Round {@code v} to {@code places} decimal places (half-up), e.g. {@code toPlaces(1.2345, 2) = 1.23}. */
    public static double toPlaces(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
