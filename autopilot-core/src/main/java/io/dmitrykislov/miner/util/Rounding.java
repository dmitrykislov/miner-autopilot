package io.dmitrykislov.miner.util;

/** Small numeric helpers shared across the mappers/DTOs. */
public final class Rounding {

    private Rounding() {}

    /**
     * Round {@code v} to {@code places} decimal places (half-up), e.g. {@code toPlaces(1.2345, 2) = 1.23}.
     *
     * <p>A value that isn't a finite number is returned <b>unchanged</b>, and so is one too large to
     * round without losing its magnitude. That matters more than it looks: this sits on the path from a
     * raw device reading to the autopilot's surplus, and everything downstream screens bad readings
     * with {@code Double.isFinite}. {@code Math.round} would answer that screening question wrongly —
     * it maps {@code +∞} to {@code Long.MAX_VALUE} and {@code NaN} to {@code 0}, so an unusable reading
     * came out the far side looking like a perfectly plausible measurement (an infinite solar reading
     * became ~9.2e18 W of "surplus", which passed every guard and would have driven the miner to its
     * ceiling). Passing the bad value through keeps it detectable.
     */
    public static double toPlaces(double v, int places) {
        // Beyond this, a double has no fractional part left to round, and scaling only risks overflow.
        if (!Double.isFinite(v) || Math.abs(v) >= 1e15) return v;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
