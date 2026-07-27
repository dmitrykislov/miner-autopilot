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
        if (samples.isEmpty()) return OptionalDouble.empty();
        Instant newest = samples.peekLast().at();
        if (Duration.between(newest, now).compareTo(freshWithin) > 0) {
            return OptionalDouble.empty(); // feed stale → unknown
        }
        Instant from = now.minus(window);
        double sum = 0;
        int n = 0;
        for (Sample s : samples) {
            if (!s.at().isBefore(from) && !s.at().isAfter(now)) {
                sum += s.value();
                n++;
            }
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / n);
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
