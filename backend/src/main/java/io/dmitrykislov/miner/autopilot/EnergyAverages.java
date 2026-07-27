package io.dmitrykislov.miner.autopilot;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Maintains time-averaged solar and house-consumption signals for the {@link AutopilotGovernor}.
 * The pollers feed it raw samples ({@link #recordSolar}/{@link #recordConsumption}); it exposes
 * the averaged <b>margin</b> (solar − consumption) over a short and a long window.
 *
 * <p>A margin is available only when <b>both</b> feeds are fresh in that window — so if either the
 * inverter or Solar Analytics goes quiet, the margin is empty and the governor treats it as
 * "unknown" (→ safe stop), never as zero. Pure aside from holding the two {@link RollingWindow}s;
 * the windows themselves are clock-injected, so this is unit-testable without real time.
 */
public class EnergyAverages {

    /** Averaged signals at a point in time; margins empty when a feed is missing/stale. */
    public record Signals(OptionalDouble shortMarginW, OptionalDouble longMarginW,
                          OptionalDouble solarShortW, OptionalDouble consumptionShortW) {}

    private final RollingWindow solar;
    private final RollingWindow consumption;
    private final Duration shortWindow;
    private final Duration longWindow;

    public EnergyAverages(Duration shortWindow, Duration longWindow, Duration freshWithin) {
        if (shortWindow.compareTo(longWindow) > 0) {
            throw new IllegalArgumentException("shortWindow must be ≤ longWindow");
        }
        this.shortWindow = shortWindow;
        this.longWindow = longWindow;
        this.solar = new RollingWindow(longWindow, freshWithin);
        this.consumption = new RollingWindow(longWindow, freshWithin);
    }

    public void recordSolar(Instant at, double watts) {
        solar.add(at, watts);
    }

    public void recordConsumption(Instant at, double watts) {
        consumption.add(at, watts);
    }

    /** Averaged margin (solar − consumption) over {@code window}; empty if either feed is missing/stale. */
    public OptionalDouble marginAvg(Instant now, Duration window) {
        OptionalDouble s = solar.average(now, window);
        OptionalDouble c = consumption.average(now, window);
        if (s.isEmpty() || c.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(s.getAsDouble() - c.getAsDouble());
    }

    public Signals signals(Instant now) {
        return new Signals(
                marginAvg(now, shortWindow),
                marginAvg(now, longWindow),
                solar.average(now, shortWindow),
                consumption.average(now, shortWindow));
    }
}
