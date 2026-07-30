package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.port.TelemetrySample;

import java.time.Duration;
import java.util.List;

/**
 * Approximate <b>energy</b> the miner has consumed over a set of telemetry samples, as the area
 * under its power curve. The area under a power(W)-vs-time(h) curve is energy (W × h = watt-hours),
 * not power — this returns watt-hours.
 *
 * <p>Per-sample power uses the miner's live <b>draw</b> ({@code minerDrawW}) when present — that's
 * the actual consumption — falling back to the configured <b>target</b> ({@code minerPowerW}) when a
 * mining sample momentarily lacks a draw reading, and <b>0</b> when the miner is off. Consecutive
 * samples are joined with the trapezoid rule.
 *
 * <p>A gap between samples longer than {@code maxGap} (e.g. the app was down) is deliberately
 * <b>not</b> integrated across — we don't invent energy for a period we never observed. The result
 * is approximate: it samples a continuously-varying draw at the (~1 min) record cadence.
 *
 * <p>Samples are assumed time-ascending (the store returns them that way); any non-positive step is
 * skipped defensively, so out-of-order or duplicate-timestamp input can never subtract energy.
 */
public final class MinerEnergy {

    private MinerEnergy() {}

    /** Approximate miner energy in <b>watt-hours</b> over {@code samples} (time-ascending). */
    public static double approxConsumedWh(List<TelemetrySample> samples, Duration maxGap) {
        if (samples == null || samples.size() < 2) return 0.0;
        long maxGapSec = Math.max(1L, maxGap.toSeconds());
        double wh = 0.0;
        TelemetrySample prev = samples.get(0);
        for (int i = 1; i < samples.size(); i++) {
            TelemetrySample cur = samples.get(i);
            if (prev.at() != null && cur.at() != null) {
                long dt = Duration.between(prev.at(), cur.at()).getSeconds();
                if (dt > 0 && dt <= maxGapSec) {
                    // trapezoid: average power over the step × step duration in hours
                    wh += (power(prev) + power(cur)) / 2.0 * (dt / 3600.0);
                }
            }
            prev = cur;
        }
        return wh;
    }

    /** The miner's power (W) at a sample: live draw if known, else the target if mining, else 0. */
    private static double power(TelemetrySample s) {
        if (s.minerDrawW() != null) return s.minerDrawW();
        if (s.minerPowerW() != null) return s.minerPowerW();
        return 0.0;
    }
}
