package io.dmitrykislov.miner.autopilot;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Maintains time-averaged solar and house-consumption signals for the {@link AutopilotGovernor}.
 * The pollers feed it raw samples ({@link #recordSolar}/{@link #recordConsumption}); it exposes
 * the averaged <b>margin</b> (solar − consumption) over a short and a long window, each requiring
 * a minimum coverage so a too-sparse window (just after boot / after a gap) reports empty.
 *
 * <p>A margin is available only when <b>both</b> feeds are fresh and adequately covered in that
 * window. {@link Signals#dataFresh()} is reported separately so the governor can tell a
 * <em>stale feed</em> (→ stop a running miner) apart from a merely <em>sparse</em> window (→ hold,
 * don't disrupt a healthy miner right after boot).
 */
public class EnergyAverages {

    /**
     * Averaged signals at a point in time.
     *
     * @param shortMarginW      short-window margin, empty if a feed is missing/stale/under-covered
     * @param longMarginW       long-window margin, same
     * @param dataFresh         both feeds have a recent sample (distinguishes stale from sparse)
     * @param solarShortW       short-window solar average (for status/UI)
     * @param consumptionShortW short-window consumption average (for status/UI)
     */
    public record Signals(OptionalDouble shortMarginW, OptionalDouble longMarginW, boolean dataFresh,
                          OptionalDouble solarShortW, OptionalDouble consumptionShortW) {}

    private final RollingWindow solar;
    private final RollingWindow consumption;
    private final Duration shortWindow;
    private final Duration longWindow;
    private final Duration shortCoverage;
    private final Duration longCoverage;

    public EnergyAverages(Duration shortWindow, Duration longWindow, Duration freshWithin,
                          Duration shortCoverage, Duration longCoverage) {
        if (shortWindow.compareTo(longWindow) > 0) {
            throw new IllegalArgumentException("shortWindow must be ≤ longWindow");
        }
        if (freshWithin.compareTo(shortWindow) > 0) {
            throw new IllegalArgumentException("freshWithin must be ≤ shortWindow (fresh must imply in-window)");
        }
        if (shortCoverage.isNegative() || shortCoverage.compareTo(shortWindow) > 0) {
            throw new IllegalArgumentException("shortCoverage must be in [0, shortWindow]");
        }
        if (longCoverage.isNegative() || longCoverage.compareTo(longWindow) > 0) {
            throw new IllegalArgumentException("longCoverage must be in [0, longWindow]");
        }
        this.shortWindow = shortWindow;
        this.longWindow = longWindow;
        this.shortCoverage = shortCoverage;
        this.longCoverage = longCoverage;
        this.solar = new RollingWindow(longWindow, freshWithin);
        this.consumption = new RollingWindow(longWindow, freshWithin);
    }

    public void recordSolar(Instant at, double watts) {
        solar.add(at, watts);
    }

    public void recordConsumption(Instant at, double watts) {
        consumption.add(at, watts);
    }

    /** Drop all recorded samples (both feeds). Used by tests to isolate the shared instance. */
    public void clear() {
        solar.clear();
        consumption.clear();
    }

    /** Averaged margin (solar − consumption) over {@code window} with the given coverage; empty if either feed is unavailable. */
    public OptionalDouble marginAvg(Instant now, Duration window, Duration minCoverage) {
        OptionalDouble s = solar.average(now, window, minCoverage);
        OptionalDouble c = consumption.average(now, window, minCoverage);
        if (s.isEmpty() || c.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(s.getAsDouble() - c.getAsDouble());
    }

    /** True when both feeds have a recent sample — i.e. we are not blind (as opposed to merely sparse). */
    public boolean dataFresh(Instant now) {
        return solar.isFresh(now) && consumption.isFresh(now);
    }

    public Signals signals(Instant now) {
        return new Signals(
                marginAvg(now, shortWindow, shortCoverage),
                marginAvg(now, longWindow, longCoverage),
                dataFresh(now),
                solar.average(now, shortWindow),
                consumption.average(now, shortWindow));
    }
}
