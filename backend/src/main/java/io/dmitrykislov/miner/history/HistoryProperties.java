package io.dmitrykislov.miner.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the lightweight telemetry history (bound from {@code house.history.*}).
 * Records solar/consumption/miner samples plus autopilot power-change events to plain append-only
 * files on disk, keeps at most {@link #retentionDays} days, and serves them to the UI chart.
 */
@ConfigurationProperties(prefix = "house.history")
public record HistoryProperties(
        // Master switch — when false nothing is recorded and the chart is empty.
        boolean enabled,
        // Directory for the append-only data files (created if absent). Relative to the working dir.
        String dir,
        // How often (ms) to append a telemetry sample.
        long recordIntervalMs,
        // Days of history to keep; anything older is discarded (in memory and on disk).
        int retentionDays) {

    public HistoryProperties {
        if (dir == null || dir.isBlank()) dir = "data/history";
        if (recordIntervalMs == 0) recordIntervalMs = 60_000; // 1 sample/min ≈ 43k rows/month
        if (retentionDays <= 0) retentionDays = 31;
    }

    public Duration retention() {
        return Duration.ofDays(retentionDays);
    }
}
