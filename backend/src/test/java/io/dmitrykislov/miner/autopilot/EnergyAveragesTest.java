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
        assertThat(a.marginAvg(at(5), SHORT, ZERO).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    @Test void marginEmptyWhenConsumptionMissing() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        assertThat(a.marginAvg(at(5), SHORT, ZERO)).isEmpty();
    }

    @Test void marginEmptyWhenSolarMissing() {
        EnergyAverages a = avg();
        a.recordConsumption(at(0), 1500);
        assertThat(a.marginAvg(at(5), SHORT, ZERO)).isEmpty();
    }

    @Test void marginEmptyWhenAFeedGoesStale() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.marginAvg(at(91), SHORT, ZERO)).isEmpty(); // > 90s fresh bound
    }

    // ---- coverage ----
    @Test void marginRequiresCoverage() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordSolar(at(10), 4000);
        a.recordConsumption(at(0), 1500);
        a.recordConsumption(at(10), 1500);
        assertThat(a.marginAvg(at(10), SHORT, SHORT_COV)).isEmpty();      // only 10s of data < 60s coverage
        assertThat(a.marginAvg(at(10), SHORT, ZERO).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    @Test void marginAvailableOnceWellCovered() {
        EnergyAverages a = avg();
        feed(a, 120, 4000, 1500);   // 120s of data ≥ 60s coverage
        assertThat(a.marginAvg(at(130), SHORT, SHORT_COV).getAsDouble()).isCloseTo(2500, within(1e-6));
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
        double shortM = a.marginAvg(at(900), SHORT, ZERO).getAsDouble();
        double longM = a.marginAvg(at(900), LONG, ZERO).getAsDouble();
        assertThat(shortM).isGreaterThan(longM);
    }

    @Test void signalsBundleShortLongAndFreshness() {
        EnergyAverages a = avg();
        feed(a, 360, 5000, 1000);   // 6 min of data → both windows well covered
        var sig = a.signals(at(360));
        assertThat(sig.dataFresh()).isTrue();
        assertThat(sig.shortMarginW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.longMarginW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(5000, within(1e-6));
        assertThat(sig.consumptionShortW().getAsDouble()).isCloseTo(1000, within(1e-6));
    }

    @Test void signalsSparseAtBootAreFreshButMarginsEmpty() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 5000);
        a.recordConsumption(at(0), 1000);
        var sig = a.signals(at(20)); // fresh (20s) but < 60s short coverage
        assertThat(sig.dataFresh()).isTrue();       // not blind…
        assertThat(sig.shortMarginW()).isEmpty();   // …but not enough coverage to trust yet
        assertThat(sig.longMarginW()).isEmpty();
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
