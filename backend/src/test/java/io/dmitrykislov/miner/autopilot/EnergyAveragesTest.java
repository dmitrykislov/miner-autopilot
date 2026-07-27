package io.dmitrykislov.miner.autopilot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Tests for the averaged solar/consumption → margin signals (coverage + freshness). */
class EnergyAveragesTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");
    private static final Duration SHORT = Duration.ofMinutes(3);
    private static final Duration LONG = Duration.ofMinutes(15);
    private static final Duration FRESH = Duration.ofSeconds(90);
    private static final Duration SHORT_COV = Duration.ofSeconds(60);
    private static final Duration LONG_COV = Duration.ofMinutes(5);
    private static final Duration ZERO = Duration.ZERO;

    private EnergyAverages avg() {
        return new EnergyAverages(SHORT, LONG, FRESH, SHORT_COV, LONG_COV);
    }

    private Instant at(long sec) {
        return T0.plusSeconds(sec);
    }

    /** Feed both feeds every 15 s from 0..endSec with the given constant values. */
    private void feed(EnergyAverages a, long endSec, double solarW, double consW) {
        for (long s = 0; s <= endSec; s += 15) {
            a.recordSolar(at(s), solarW);
            a.recordConsumption(at(s), consW);
        }
    }

    // ---- margin arithmetic (no coverage requirement) ----
    @Test void marginIsSolarMinusConsumption() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.surplusAvg(at(5), SHORT, ZERO).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    @Test void marginEmptyWhenConsumptionMissing() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        assertThat(a.surplusAvg(at(5), SHORT, ZERO)).isEmpty();
    }

    @Test void marginEmptyWhenSolarMissing() {
        EnergyAverages a = avg();
        a.recordConsumption(at(0), 1500);
        assertThat(a.surplusAvg(at(5), SHORT, ZERO)).isEmpty();
    }

    @Test void marginEmptyWhenAFeedGoesStale() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.surplusAvg(at(91), SHORT, ZERO)).isEmpty(); // > 90s fresh bound
    }

    // ---- coverage ----
    @Test void marginRequiresCoverage() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordSolar(at(10), 4000);
        a.recordConsumption(at(0), 1500);
        a.recordConsumption(at(10), 1500);
        assertThat(a.surplusAvg(at(10), SHORT, SHORT_COV)).isEmpty();      // only 10s of data < 60s coverage
        assertThat(a.surplusAvg(at(10), SHORT, ZERO).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    @Test void marginAvailableOnceWellCovered() {
        EnergyAverages a = avg();
        feed(a, 120, 4000, 1500);   // 120s of data ≥ 60s coverage
        assertThat(a.surplusAvg(at(130), SHORT, SHORT_COV).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    // ---- dataFresh (stale vs sparse) ----
    @Test void dataFreshTrueWhenBothRecent() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.dataFresh(at(30))).isTrue();     // 30s < 90s
    }

    @Test void dataFreshFalseWhenAFeedIsStale() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.dataFresh(at(120))).isFalse();   // > 90s
    }

    @Test void dataFreshFalseWhenAFeedNeverFed() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000); // no consumption ever
        assertThat(a.dataFresh(at(10))).isFalse();
    }

    // ---- trend + bundle ----
    @Test void shortAndLongWindowsDifferOnATrend() {
        EnergyAverages a = avg();
        for (int s = 0; s <= 900; s += 30) {
            a.recordSolar(at(s), 1000 + (3000.0 * s / 900)); // solar ramps up
            a.recordConsumption(at(s), 1000);                // consumption flat
        }
        double shortM = a.surplusAvg(at(900), SHORT, ZERO).getAsDouble();
        double longM = a.surplusAvg(at(900), LONG, ZERO).getAsDouble();
        assertThat(shortM).isGreaterThan(longM);
    }

    @Test void signalsBundleShortLongAndFreshness() {
        EnergyAverages a = avg();
        feed(a, 360, 5000, 1000);   // 6 min of data → both windows well covered
        var sig = a.signals(at(360));
        assertThat(sig.dataFresh()).isTrue();
        assertThat(sig.shortSurplusW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.longSurplusW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(5000, within(1e-6));
        assertThat(sig.consumptionShortW().getAsDouble()).isCloseTo(1000, within(1e-6));
    }

    @Test void signalsSparseAtBootAreFreshButMarginsEmpty() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 5000);
        a.recordConsumption(at(0), 1000);
        var sig = a.signals(at(20)); // fresh (20s) but < 60s short coverage
        assertThat(sig.dataFresh()).isTrue();       // not blind…
        assertThat(sig.shortSurplusW()).isEmpty();   // …but not enough coverage to trust yet
        assertThat(sig.longSurplusW()).isEmpty();
    }

    // ---- miner-draw correction (the fix for the post-power-change spurious stop) ----
    @Test void surplusAddsBackTheMinerDraw() {
        EnergyAverages a = avg();
        // solar 4000, house 3000 (includes a 2000 W miner), so base = 1000 → true surplus = 3000.
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 3000);
        a.recordMinerDraw(at(0), 2000);
        assertThat(a.surplusAvg(at(5), SHORT, ZERO).getAsDouble()).isCloseTo(3000, within(1e-6)); // 4000−3000+2000
    }

    @Test void surplusIsImmuneToAPowerChangeMidWindow() {
        // Constant true surplus of 3000 W (solar 4000, base 1000). The miner draw changes from 2000
        // to 800 mid-window; measured consumption follows (base + draw). The averaged surplus must
        // stay 3000 regardless of when the draw changed — this is what kills the spurious post-step STOP.
        EnergyAverages a = avg();
        for (long s = 0; s <= 120; s += 15) {
            double draw = s < 60 ? 2000 : 800;      // draw drops mid-window
            a.recordSolar(at(s), 4000);
            a.recordConsumption(at(s), 1000 + draw); // house = base(1000) + draw
            a.recordMinerDraw(at(s), draw);
        }
        // avg(margin) is contaminated (mix of −(1000..) values) but avg(margin)+avg(draw) = 3000.
        assertThat(a.surplusAvg(at(120), SHORT, ZERO).getAsDouble()).isCloseTo(3000, within(1e-6));
    }

    @Test void surplusEqualsMarginWhenMinerNotDrawing() {
        // No draw recorded (miner off) → draw term is 0 → surplus == solar − consumption.
        EnergyAverages a = avg();
        a.recordSolar(at(0), 5000);
        a.recordConsumption(at(0), 1200);
        assertThat(a.surplusAvg(at(5), SHORT, ZERO).getAsDouble()).isCloseTo(3800, within(1e-6));
    }

    // ---- validation ----
    @Test void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new EnergyAverages(LONG, SHORT, FRESH, SHORT_COV, LONG_COV))
                .isInstanceOf(IllegalArgumentException.class); // short > long
        assertThatThrownBy(() -> new EnergyAverages(SHORT, LONG, Duration.ofMinutes(5), SHORT_COV, LONG_COV))
                .isInstanceOf(IllegalArgumentException.class); // freshWithin > shortWindow
        assertThatThrownBy(() -> new EnergyAverages(SHORT, LONG, FRESH, Duration.ofMinutes(5), LONG_COV))
                .isInstanceOf(IllegalArgumentException.class); // shortCoverage > shortWindow
        assertThatThrownBy(() -> new EnergyAverages(SHORT, LONG, FRESH, SHORT_COV, Duration.ofMinutes(20)))
                .isInstanceOf(IllegalArgumentException.class); // longCoverage > longWindow
        assertThatThrownBy(() -> new EnergyAverages(SHORT, LONG, FRESH, Duration.ofSeconds(-1), LONG_COV))
                .isInstanceOf(IllegalArgumentException.class); // negative shortCoverage
        assertThatThrownBy(() -> new EnergyAverages(SHORT, LONG, FRESH, SHORT_COV, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class); // negative longCoverage
    }

    @Test void validConstructionWithZeroCoverageIsAllowed() {
        assertThatCode(() -> new EnergyAverages(SHORT, LONG, FRESH, ZERO, ZERO))
                .doesNotThrowAnyException();
    }
}
