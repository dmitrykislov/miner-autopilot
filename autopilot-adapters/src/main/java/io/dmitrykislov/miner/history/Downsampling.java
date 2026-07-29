package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.port.TelemetrySample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reduces a (time-ascending) list of {@link TelemetrySample}s to at most {@code maxPoints} by
 * bucket-averaging, so a month of minute-resolution data still draws as a smooth, small chart.
 * Numeric fields are averaged over each bucket's non-null values (null if the whole bucket is null);
 * the bucket timestamp is the mean instant; the miner state is the last non-null in the bucket.
 */
public final class Downsampling {

    private Downsampling() {}

    public static List<TelemetrySample> reduce(List<TelemetrySample> in, int maxPoints) {
        if (maxPoints < 1) throw new IllegalArgumentException("maxPoints must be ≥ 1");
        int n = in.size();
        if (n <= maxPoints) return in;

        List<TelemetrySample> out = new ArrayList<>(maxPoints);
        for (int b = 0; b < maxPoints; b++) {
            int from = (int) ((long) b * n / maxPoints);
            int to = (int) ((long) (b + 1) * n / maxPoints); // exclusive
            if (to <= from) continue; // shouldn't happen while maxPoints ≤ n, but be safe
            out.add(bucket(in, from, to));
        }
        return out;
    }

    private static TelemetrySample bucket(List<TelemetrySample> in, int from, int to) {
        double solarSum = 0, consSum = 0, powSum = 0, drawSum = 0, tsSum = 0;
        int solarN = 0, consN = 0, powN = 0, drawN = 0;
        String state = null;
        for (int i = from; i < to; i++) {
            TelemetrySample s = in.get(i);
            tsSum += s.at().toEpochMilli();
            if (s.solarW() != null) { solarSum += s.solarW(); solarN++; }
            if (s.consumptionW() != null) { consSum += s.consumptionW(); consN++; }
            if (s.minerPowerW() != null) { powSum += s.minerPowerW(); powN++; }
            if (s.minerDrawW() != null) { drawSum += s.minerDrawW(); drawN++; }
            if (s.minerState() != null) state = s.minerState();
        }
        int count = to - from;
        return new TelemetrySample(
                Instant.ofEpochMilli(Math.round(tsSum / count)),
                solarN > 0 ? solarSum / solarN : null,
                consN > 0 ? consSum / consN : null,
                powN > 0 ? (int) Math.round(powSum / powN) : null,
                drawN > 0 ? (int) Math.round(drawSum / drawN) : null,
                state);
    }
}
