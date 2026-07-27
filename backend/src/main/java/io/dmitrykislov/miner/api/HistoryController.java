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
 * Serves recorded telemetry for the history chart. Two ways to ask for a window:
 * <ul>
 *   <li>{@code GET /api/history?from=<ms>&to=<ms>} — an explicit epoch-millis range (the UI uses
 *       this to show a specific span and to step back/forward in time);</li>
 *   <li>{@code GET /api/history?hours=<n>} — the last {@code n} hours (convenience).</li>
 * </ul>
 * The range is clamped to {@code [now − retention, now]}, samples are downsampled to a
 * chart-friendly count, and the autopilot power-change events in the window are returned in full.
 * Like every {@code /api/**} endpoint it requires a valid auth token.
 */
@RestController
@RequestMapping("/api/history")
@CrossOrigin
public class HistoryController {

    /** Cap on samples returned per request — keeps even a full day a small, smooth chart. */
    private static final int MAX_POINTS = 1500;

    private final TelemetryStore store;
    private final HistoryProperties cfg;

    public HistoryController(TelemetryStore store, HistoryProperties cfg) {
        this.store = store;
        this.cfg = cfg;
    }

    @GetMapping
    public HistoryResponse history(@RequestParam(required = false) Long from,
                                   @RequestParam(required = false) Long to,
                                   @RequestParam(required = false) Integer hours) {
        Instant now = Instant.now();
        Instant retentionStart = now.minus(cfg.retention());

        Instant toI = to != null ? Instant.ofEpochMilli(to) : now;
        if (toI.isAfter(now)) toI = now;                       // never beyond now
        if (toI.isBefore(retentionStart)) toI = retentionStart; // whole window is off the end → empty-ish

        Instant fromI;
        if (from != null) {
            fromI = Instant.ofEpochMilli(from);
        } else {
            long h = hours != null ? Math.max(1, hours) : 12; // default: last 12h
            fromI = toI.minus(Duration.ofHours(h));
        }
        if (fromI.isBefore(retentionStart)) fromI = retentionStart; // clamp to what we keep
        if (!fromI.isBefore(toI)) fromI = toI.minus(Duration.ofMinutes(1)); // guarantee from < to

        List<TelemetrySample> samples = Downsampling.reduce(store.samplesBetween(fromI, toI), MAX_POINTS);
        List<PowerChangeEvent> events = store.eventsBetween(fromI, toI);
        return new HistoryResponse(fromI, toI, cfg.retentionDays(), cfg.recordIntervalMs(), samples, events);
    }
}
