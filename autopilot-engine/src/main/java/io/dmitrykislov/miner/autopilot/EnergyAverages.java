package io.dmitrykislov.miner.autopilot;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Maintains time-averaged solar, house-consumption and miner-draw signals for the
 * {@link AutopilotGovernor}. The pollers feed it raw samples ({@link #recordSolar} /
 * {@link #recordConsumption} / {@link #recordMinerDraw}); it exposes the averaged <b>miner-independent
 * surplus</b> over a short and a long window, each requiring a minimum coverage so a too-sparse
 * window (just after boot / after a gap) reports empty.
 *
 * <p><b>Why the miner draw is tracked.</b> The surplus a miner can safely draw is
 * {@code solar − base-house-load}, where base = measured consumption − the miner's own draw. Rather
 * than reconstruct it as {@code avg(solar − consumption) + currentPower} — which is wrong for a
 * window straddling a power change, because the instantaneous {@code currentPower} no longer matches
 * the draw baked into the averaged consumption — we average the draw too:
 * <pre>surplus = avg(solar) − avg(consumption) + avg(minerDraw) = avg(solar − base)</pre>
 * Averaging is linear, so this equals the true averaged surplus regardless of when the miner's power
 * changed within the window. That removes the class of spurious stops/steps that a stale
 * margin-plus-instantaneous-power estimate produced right after the autopilot moved the miner.
 *
 * <p>A surplus is available only when <b>both</b> the solar and consumption feeds are fresh and
 * adequately covered in that window; the draw term contributes 0 when the miner isn't reporting (it
 * isn't drawing). {@link Signals#dataFresh()} is reported separately so the governor can tell a
 * <em>stale feed</em> (→ stop a running miner) apart from a merely <em>sparse</em> window (→ hold).
 */
public class EnergyAverages {

    /**
     * Averaged signals at a point in time.
     *
     * @param shortSurplusW     short-window miner-independent surplus, empty if under-covered/stale
     * @param longSurplusW      long-window surplus, same
     * @param dataFresh         both feeds have a recent sample (distinguishes stale from sparse)
     * @param solarShortW       short-window solar average (for status/UI)
     * @param consumptionShortW short-window consumption average (for status/UI)
     */
    public record Signals(OptionalDouble shortSurplusW, OptionalDouble longSurplusW, boolean dataFresh,
                          OptionalDouble solarShortW, OptionalDouble consumptionShortW) {}

    private final RollingWindow solar;
    private final RollingWindow consumption;
    private final RollingWindow minerDraw;
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
        this.minerDraw = new RollingWindow(longWindow, freshWithin);
    }

    public void recordSolar(Instant at, double watts) {
        solar.add(at, watts);
    }

    public void recordConsumption(Instant at, double watts) {
        consumption.add(at, watts);
    }

    /** The miner's own draw at {@code at} (0 when it isn't mining). Co-sampled with consumption. */
    public void recordMinerDraw(Instant at, double watts) {
        minerDraw.add(at, watts);
    }

    // Clock-aware variants: a sample dated after `now` is a clock artifact and is ignored (a Pi has
    // no RTC, so NTP can step the clock backwards past already-recorded timestamps). Production
    // callers use these; the 2-arg forms above trust their timestamp and serve tests/backfill.

    public void recordSolar(Instant at, double watts, Instant now) {
        solar.add(at, watts, now);
    }

    public void recordConsumption(Instant at, double watts, Instant now) {
        consumption.add(at, watts, now);
    }

    public void recordMinerDraw(Instant at, double watts, Instant now) {
        minerDraw.add(at, watts, now);
    }

    /** Drop all recorded samples. Used by tests to isolate the shared instance. */
    public void clear() {
        solar.clear();
        consumption.clear();
        minerDraw.clear();
    }

    /**
     * Averaged miner-independent surplus over {@code window}: {@code avg(solar) − avg(consumption)
     * + avg(minerDraw)}. Empty if either the solar or consumption feed is missing/stale/under-covered.
     * The draw term is 0 when the miner isn't reporting a draw (it isn't mining), so a stopped/off
     * miner correctly yields {@code surplus = avg(solar − house)}.
     */
    public OptionalDouble surplusAvg(Instant now, Duration window, Duration minCoverage) {
        OptionalDouble s = solar.average(now, window, minCoverage);
        OptionalDouble c = consumption.average(now, window, minCoverage);
        if (s.isEmpty() || c.isEmpty()) return OptionalDouble.empty();
        double draw = minerDraw.average(now, window).orElse(0.0);
        return OptionalDouble.of(s.getAsDouble() - c.getAsDouble() + draw);
    }

    /** True when both feeds have a recent sample — i.e. we are not blind (as opposed to merely sparse). */
    public boolean dataFresh(Instant now) {
        return solar.isFresh(now) && consumption.isFresh(now);
    }

    public Signals signals(Instant now) {
        return new Signals(
                surplusAvg(now, shortWindow, shortCoverage),
                surplusAvg(now, longWindow, longCoverage),
                dataFresh(now),
                solar.average(now, shortWindow),
                consumption.average(now, shortWindow));
    }
}
