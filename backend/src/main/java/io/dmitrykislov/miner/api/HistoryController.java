package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.history.Downsampling;
import io.dmitrykislov.miner.history.HistoryProperties;
import io.dmitrykislov.miner.history.HistoryResponse;
import io.dmitrykislov.miner.history.PowerChangeEvent;
import io.dmitrykislov.miner.history.TelemetrySample;
import io.dmitrykislov.miner.history.TelemetryStore;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Serves recorded telemetry for the history chart:
 * {@code GET /api/history?hours=24} → solar/consumption/miner samples (downsampled to a
 * chart-friendly count) plus the autopilot power-change events in that window. Like every
 * {@code /api/**} endpoint it requires a valid auth token.
 */
@RestController
@RequestMapping("/api/history")
@CrossOrigin
public class HistoryController {

    /** Cap on samples returned per request — keeps a month of data a small, smooth chart. */
    private static final int MAX_POINTS = 1500;

    private final TelemetryStore store;
    private final HistoryProperties cfg;

    public HistoryController(TelemetryStore store, HistoryProperties cfg) {
        this.store = store;
        this.cfg = cfg;
    }

    @GetMapping
    public HistoryResponse history(@RequestParam(defaultValue = "24") int hours) {
        int maxHours = cfg.retentionDays() * 24;
        int h = Math.max(1, Math.min(hours, maxHours)); // clamp to [1h, retention]
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofHours(h));
        List<TelemetrySample> samples = Downsampling.reduce(store.samplesSince(from), MAX_POINTS);
        List<PowerChangeEvent> events = store.eventsSince(from);
        return new HistoryResponse(from, now, cfg.retentionDays(), cfg.recordIntervalMs(), samples, events);
    }
}
