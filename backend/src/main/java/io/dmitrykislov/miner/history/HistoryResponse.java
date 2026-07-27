package io.dmitrykislov.miner.history;

import java.time.Instant;
import java.util.List;

/**
 * The history-chart payload for a requested window.
 *
 * @param from          window start (inclusive)
 * @param to            window end (now)
 * @param retentionDays how many days of history are kept (for the UI's window selector)
 * @param intervalMs    the nominal sampling interval (raw, before any downsampling)
 * @param samples       telemetry samples for the window, downsampled to a chart-friendly count
 * @param events        autopilot power-change events in the window (never downsampled)
 */
public record HistoryResponse(
        Instant from,
        Instant to,
        int retentionDays,
        long intervalMs,
        List<TelemetrySample> samples,
        List<PowerChangeEvent> events) {
}
