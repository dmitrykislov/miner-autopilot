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

    @Test void coverageBoundaryIsInclusive() {
        RollingWindow w = window();
        // sample at exactly (now − minCoverage) satisfies coverage
        w.add(at(0), 1000);   // this is exactly at coverageBy for now=60, minCoverage=60
        w.add(at(60), 1000);
        assertThat(w.average(at(60), Duration.ofMinutes(3), Duration.ofSeconds(60)).getAsDouble())
                .isCloseTo(1000, within(1e-6));
    }

    @Test void outOfOrderAddKeepsFreshnessAndAverageCorrect() {
        RollingWindow w = window();
        w.add(at(100), 1000);
        w.add(at(50), 2000);   // arrives late with an older timestamp
        // newest is at(100), NOT the last-added at(50): fresh at t=120 (20s old)
        assertThat(w.isFresh(at(120))).isTrue();
        // both samples fall in a 3-min window at t=120 → mean 1500
        assertThat(w.average(at(120), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(1500, within(1e-6));
        // staleness is judged from the newest timestamp (100), not the last-added (50)
        assertThat(w.isFresh(at(200))).isFalse(); // 100s old > 60s fresh bound
    }

    @Test void outOfOrderAddStillPrunesAgainstTheNewest() {
        RollingWindow w = window(); // retain 15 min = 900s
        w.add(at(1000), 1);
        w.add(at(50), 2);   // 950s before the newest → older than retain → pruned on insert
        assertThat(w.size()).isEqualTo(1);
        assertThat(w.average(at(1000), Duration.ofMinutes(15)).getAsDouble()).isCloseTo(1, within(1e-6));
    }

    @Test void clearEmptiesTheWindow() {
        RollingWindow w = window();
        w.add(at(0), 1000);
        w.clear();
        assertThat(w.size()).isZero();
        assertThat(w.average(at(1), Duration.ofMinutes(3))).isEmpty();
    }

    // ---- clock skew: a sample dated in the future is a clock artifact, never a fresh feed -------
    // A Raspberry Pi has no RTC: it boots on the saved (stale) time and NTP may step the clock
    // BACKWARDS, so samples written moments earlier can end up timestamped in the future.

    @Test void aFutureDatedSampleDoesNotReportTheFeedAsFresh() {
        RollingWindow w = window();
        w.add(at(4000), 1000);          // 1h ahead of the "now" below
        // Age is negative here. Treating that as fresh is the dangerous reading: the governor would
        // see dataFresh=true with an empty average and hold a running miner instead of stopping it.
        assertThat(w.isFresh(at(400))).isFalse();
        assertThat(w.average(at(400), Duration.ofMinutes(3))).isEmpty();
    }

    @Test void addIgnoresASampleDatedAfterNow() {
        RollingWindow w = window();
        w.add(at(4000), 1000, at(400));  // bogus: 1h ahead of now → never recorded
        assertThat(w.size()).isZero();
        assertThat(w.isFresh(at(400))).isFalse();

        w.add(at(395), 500, at(400));    // a genuine reading is recorded as normal
        w.add(at(400), 700, at(400));    // at == now is valid, not "future"
        assertThat(w.size()).isEqualTo(2);
        assertThat(w.average(at(400), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(600, within(1e-6));
    }

    @Test void aFutureDatedSampleNeverReportsTheFeedAsFresh() {
        // Defence in depth: even if a future sample gets in via the timestamp-trusting 2-arg add,
        // querying must not read it as fresh. A negative age comparing as "fresh" is the dangerous
        // case — the governor would see a live feed with empty averages and hold a running miner
        // instead of stopping it, importing all night.
        RollingWindow w = window();
        w.add(at(4000), 1000);
        assertThat(w.isFresh(at(400))).isFalse();
        assertThat(w.average(at(400), Duration.ofMinutes(3))).isEmpty();
        // …and the bogus sample is dropped, so the feed recovers with the next real reading.
        w.add(at(400), 500);
        w.add(at(410), 700);
        assertThat(w.isFresh(at(410))).isTrue();
        assertThat(w.average(at(410), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(600, within(1e-6));
    }

    @Test void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new RollingWindow(Duration.ZERO, fresh)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RollingWindow(retain, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RollingWindow(null, fresh)).isInstanceOf(IllegalArgumentException.class);
    }
}
