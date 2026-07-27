package io.dmitrykislov.miner.autopilot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;

/**
 * A time-bounded rolling average of timestamped samples — the primitive behind the
 * autopilot's smoothed solar/consumption signals. Pure and clock-injected (every query
 * takes {@code now}), so it is fully unit-testable without real time.
 *
 * <p>Samples older than {@link #retain} are pruned. {@link #average(Instant, Duration)}
 * returns the mean of samples within {@code [now − window, now]}, or empty when there is
 * nothing in the window <b>or</b> the newest sample is older than {@link #freshWithin}
 * (the feed has gone stale — the caller must treat this as "unknown", never zero).
 *
 * <p><b>Why a simple mean, not a step-hold time-weighted average:</b> step-hold carries the
 * last value forward across gaps, so a missed run of polls would hold a stale reading across
 * the gap (e.g. a pre-cloud high solar value) and dominate the window — over-estimating surplus,
 * which is unsafe. A simple mean of the samples actually present degrades gracefully on gaps.
 * Sampling here is regular (~10–15 s), so for the normal case the two are equivalent anyway.
 *
 * <p>The {@code minCoverage} overload additionally requires the samples to span back at least
 * {@code minCoverage} from {@code now}, so a too-sparse window (just after boot, or right after a
 * gap) reports empty rather than a misleading average of a recent sliver.
 */
public class RollingWindow {

    private record Sample(Instant at, double value) {}

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Duration retain;
    private final Duration freshWithin;

    /**
     * @param retain      drop samples older than this (should be ≥ the longest window queried)
     * @param freshWithin if the newest sample is older than this at query time, averages are empty
     */
    public RollingWindow(Duration retain, Duration freshWithin) {
        if (retain == null || retain.isNegative() || retain.isZero()) {
            throw new IllegalArgumentException("retain must be positive");
        }
        if (freshWithin == null || freshWithin.isNegative() || freshWithin.isZero()) {
            throw new IllegalArgumentException("freshWithin must be positive");
        }
        this.retain = retain;
        this.freshWithin = freshWithin;
    }

    /** Record a sample and prune anything older than {@link #retain} relative to it. */
    public synchronized void add(Instant at, double value) {
        samples.addLast(new Sample(at, value));
        Instant cutoff = at.minus(retain);
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    /**
     * Mean of samples in {@code [now − window, now]}. Empty if the newest sample is stale
     * (older than {@code freshWithin}) or no sample falls inside the window.
     */
    public synchronized OptionalDouble average(Instant now, Duration window) {
        return average(now, window, Duration.ZERO);
    }

    /**
     * Like {@link #average(Instant, Duration)} but also requires the in-window samples to span
     * back at least {@code minCoverage} from {@code now} — otherwise (a too-sparse window) it
     * returns empty rather than an average of only the most-recent sliver.
     */
    public synchronized OptionalDouble average(Instant now, Duration window, Duration minCoverage) {
        if (samples.isEmpty()) return OptionalDouble.empty();
        Instant newest = samples.peekLast().at();
        if (Duration.between(newest, now).compareTo(freshWithin) > 0) {
            return OptionalDouble.empty(); // feed stale → unknown
        }
        Instant from = now.minus(window);
        Instant coverageBy = now.minus(minCoverage); // need an in-window sample at or before here
        boolean needCoverage = !minCoverage.isZero() && !minCoverage.isNegative();
        boolean covered = !needCoverage;
        double sum = 0;
        int n = 0;
        for (Sample s : samples) {
            if (!s.at().isBefore(from) && !s.at().isAfter(now)) {
                sum += s.value();
                n++;
                if (needCoverage && !s.at().isAfter(coverageBy)) covered = true;
            }
        }
        return (n == 0 || !covered) ? OptionalDouble.empty() : OptionalDouble.of(sum / n);
    }

    /** True if the feed is fresh (newest sample within {@code freshWithin} of {@code now}). */
    public synchronized boolean isFresh(Instant now) {
        if (samples.isEmpty()) return false;
        return Duration.between(samples.peekLast().at(), now).compareTo(freshWithin) <= 0;
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized void clear() {
        samples.clear();
    }
}
