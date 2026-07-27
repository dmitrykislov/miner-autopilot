package io.dmitrykislov.miner.autopilot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Exhaustive tests for the time-bounded rolling average. */
class RollingWindowTest {

    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");
    private final Duration retain = Duration.ofMinutes(15);
    private final Duration fresh = Duration.ofSeconds(60);

    private RollingWindow window() {
        return new RollingWindow(retain, fresh);
    }

    private Instant at(long sec) {
        return T0.plusSeconds(sec);
    }

    @Test void emptyWindowHasNoAverage() {
        assertThat(window().average(T0, Duration.ofMinutes(3))).isEmpty();
    }

    @Test void singleSampleReturnsItsValue() {
        RollingWindow w = window();
        w.add(at(0), 1500);
        assertThat(w.average(at(1), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(1500, within(1e-6));
    }

    @Test void averagesAllSamplesInsideTheWindow() {
        RollingWindow w = window();
        w.add(at(0), 1000);
        w.add(at(15), 2000);
        w.add(at(30), 3000);
        assertThat(w.average(at(30), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(2000, within(1e-6));
    }

    @Test void windowExcludesSamplesOlderThanTheWindow() {
        RollingWindow w = window();
        w.add(at(0), 5000);       // 5 min before the query → outside a 3-min window
        w.add(at(240), 1000);     // 1 min before → inside
        w.add(at(280), 1200);     // inside
        // 3-min window at t=300 covers [120, 300] → only the 1000 and 1200 samples
        assertThat(w.average(at(300), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(1100, within(1e-6));
    }

    @Test void shorterWindowSeesFewerSamplesThanLongerWindow() {
        RollingWindow w = window();
        for (int i = 0; i <= 900; i += 30) w.add(at(i), i); // ramp 0..900 every 30s over 15 min
        double shortAvg = w.average(at(900), Duration.ofMinutes(3)).getAsDouble();  // last 3 min
        double longAvg = w.average(at(900), Duration.ofMinutes(15)).getAsDouble();  // last 15 min
        assertThat(shortAvg).isGreaterThan(longAvg); // recent samples are larger (ramp up)
    }

    @Test void staleFeedYieldsNoAverage() {
        RollingWindow w = window();
        w.add(at(0), 1500);
        // query 61s later — newest sample is older than the 60s freshness bound
        assertThat(w.average(at(61), Duration.ofMinutes(3))).isEmpty();
        assertThat(w.isFresh(at(61))).isFalse();
        assertThat(w.isFresh(at(59))).isTrue();
    }

    @Test void freshCheckOnEmptyIsFalse() {
        assertThat(window().isFresh(T0)).isFalse();
    }

    @Test void prunesSamplesOlderThanRetain() {
        RollingWindow w = window();
        w.add(at(0), 1);      // will be pruned (0 < 1000−900=100 cutoff)
        w.add(at(950), 2);    // kept (≥ 100)
        w.add(at(1000), 3);   // adding this prunes anything older than 100s
        assertThat(w.size()).isEqualTo(2); // the at(0) sample dropped, at(950)/at(1000) kept
        assertThat(w.average(at(1000), Duration.ofMinutes(15)).getAsDouble()).isCloseTo(2.5, within(1e-6));
    }

    @Test void noSampleInsideWindowYieldsEmptyEvenIfFresh() {
        RollingWindow w = window();
        w.add(at(0), 1000);   // only sample
        // fresh at t=30, but a very short 10s window [20,30] contains nothing
        assertThat(w.average(at(30), Duration.ofSeconds(10))).isEmpty();
    }

    @Test void minCoverageRejectsATooSparseWindow() {
        RollingWindow w = window();
        // Two fresh samples but only spanning the last 10s — a 3-min window needs ≥60s of coverage.
        w.add(at(0), 1000);
        w.add(at(10), 1100);
        assertThat(w.average(at(10), Duration.ofMinutes(3), Duration.ofSeconds(60))).isEmpty();
        // without the coverage requirement, the same query returns the mean
        assertThat(w.average(at(10), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(1050, within(1e-6));
    }

    @Test void minCoverageAcceptsAWellSpannedWindow() {
        RollingWindow w = window();
        for (int s = 0; s <= 120; s += 15) w.add(at(s), 1000); // 120s of data
        // query at 130 (fresh: newest 10s old); needs ≥60s coverage → oldest in 3-min window is 130-180.. at 0 → covered
        assertThat(w.average(at(130), Duration.ofMinutes(3), Duration.ofSeconds(60)).getAsDouble())
                .isCloseTo(1000, within(1e-6));
    }

    @Test void minCoverageStillEmptyWhenStaleEvenIfWellSpanned() {
        RollingWindow w = window();
        for (int s = 0; s <= 120; s += 15) w.add(at(s), 1000);
        // 120 + 61 = newest is 61s old → stale → empty regardless of coverage
        assertThat(w.average(at(181), Duration.ofMinutes(3), Duration.ofSeconds(60))).isEmpty();
    }

    @Test void clearEmptiesTheWindow() {
        RollingWindow w = window();
        w.add(at(0), 1000);
        w.clear();
        assertThat(w.size()).isZero();
        assertThat(w.average(at(1), Duration.ofMinutes(3))).isEmpty();
    }

    @Test void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new RollingWindow(Duration.ZERO, fresh)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RollingWindow(retain, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RollingWindow(null, fresh)).isInstanceOf(IllegalArgumentException.class);
    }
}
