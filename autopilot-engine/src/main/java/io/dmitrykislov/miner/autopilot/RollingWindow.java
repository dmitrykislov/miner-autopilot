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
    private Instant newest; // max sample timestamp seen (null when empty); order-independent

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

    /**
     * Record a sample and prune anything older than {@link #retain} relative to the newest
     * timestamp seen. Tolerant of out-of-order arrivals: freshness and pruning track the
     * maximum timestamp, not merely the last-added one, so a late sample can't make the feed
     * look stale or shift the retention cutoff backwards.
     */
    public synchronized void add(Instant at, double value) {
        add(at, value, at); // timestamp trusted as current — see the 3-arg overload
    }

    /**
     * Record a sample, <b>ignoring one dated after {@code now}</b>.
     *
     * <p>A future-dated sample is a clock artifact, not a reading, and admitting one is doubly
     * harmful: it makes {@code Duration.between(newest, now)} negative, which compares as "fresh"
     * and would let the governor believe the feed is live while every average is empty — holding a
     * running miner instead of stopping it. It also pushes the retention cutoff ahead of real
     * samples, pruning each genuine reading as it arrives. Production callers must use this
     * overload; {@link #add(Instant, double)} trusts its timestamp and exists for tests and
     * history backfill.
     */
    public synchronized void add(Instant at, double value, Instant now) {
        // A non-finite reading would make the whole average non-finite, and every later comparison
        // against it false — silently disabling the governor's safety checks. Drop it at the door.
        if (!Double.isFinite(value)) return;
        if (isImplausiblyFuture(at, now)) return;
        samples.addLast(new Sample(at, value));
        if (newest == null || at.isAfter(newest)) newest = at;
        Instant cutoff = newest.minus(retain);
        samples.removeIf(s -> s.at().isBefore(cutoff));
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
        dropFutureSamples(now);
        if (samples.isEmpty()) return OptionalDouble.empty();
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
        dropFutureSamples(now);
        if (samples.isEmpty()) return false;
        return Duration.between(newest, now).compareTo(freshWithin) <= 0;
    }

    /**
     * Discard samples timestamped after {@code now} and recompute {@link #newest}.
     *
     * <p>A sample dated in the future is a clock artifact, not a reading: a Raspberry Pi has no RTC,
     * so it boots on the last saved time and NTP may step the clock <b>backwards</b> — samples written
     * moments earlier then sit in the future. Left in place such a sample is doubly harmful. It makes
     * {@code Duration.between(newest, now)} <b>negative</b>, which compares as "fresh" and would let
     * the governor believe the feed is live while every average is empty (it would hold a running
     * miner instead of stopping it — importing all night). It also pushes the retention cutoff ahead
     * of real samples, so each genuine reading is pruned the moment it arrives.
     *
     * <p>Dropping them here — under the same lock, at every query, where {@code now} is known — makes
     * the window self-healing without giving {@link #add} a clock. Ingestion also filters future
     * samples ({@code EnergySampler}, {@code EnergyWarmup}); this is the primitive's own guarantee.
     */
    private void dropFutureSamples(Instant now) {
        if (newest == null || !isImplausiblyFuture(newest, now)) return; // fast path, the normal case
        samples.removeIf(s -> isImplausiblyFuture(s.at(), now));
        newest = null;
        for (Sample s : samples) {
            if (newest == null || s.at().isAfter(newest)) newest = s.at();
        }
    }

    /**
     * Is {@code at} so far ahead of {@code now} that it can only be a clock artifact?
     *
     * <p>A reading a moment ahead is normal and must be kept: the pollers stamp their own
     * {@code Instant.now()} on separate threads, while a caller typically captures {@code now} once and
     * then spends time on blocking device I/O before querying — so a sample can legitimately be
     * milliseconds to seconds "in the future" relative to that caller. Discarding those would throw
     * away good readings and, worse, make the feed look dead and stop a healthy miner.
     *
     * <p>A clock step is a different magnitude entirely: a Pi with no RTC boots on the saved time and
     * NTP corrects it by minutes or hours. {@code freshWithin} is the natural dividing line — it is
     * already the age at which a feed counts as stale, so anything further ahead than that cannot be
     * a plausible reading either.
     */
    private boolean isImplausiblyFuture(Instant at, Instant now) {
        return at.isAfter(now.plus(freshWithin));
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized void clear() {
        samples.clear();
        newest = null;
    }
}
