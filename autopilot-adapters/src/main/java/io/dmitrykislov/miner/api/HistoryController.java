package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.history.Downsampling;
import io.dmitrykislov.miner.history.HistoryProperties;
import io.dmitrykislov.miner.history.HistoryResponse;
import io.dmitrykislov.miner.history.MinerEnergy;
import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetrySample;
import io.dmitrykislov.miner.port.TelemetryHistory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
public class HistoryController {

    /** Cap on samples returned per request — keeps even a full day a small, smooth chart. */
    private static final int MAX_POINTS = 1500;

    private final TelemetryHistory store;
    private final HistoryProperties cfg;

    public HistoryController(TelemetryHistory store, HistoryProperties cfg) {
        this.store = store;
        this.cfg = cfg;
    }

    /**
     * The chart window. Runs on {@link Schedulers#boundedElastic()}: it walks the whole in-memory
     * series (up to ~45k samples at a month's retention) while holding the store's lock — the same
     * lock the recorder takes to write to the SD card. On a Netty event loop, and there are only two
     * on the Pi, a slow card would stall every other request and SSE write queued behind it.
     */
    @GetMapping
    public Mono<HistoryResponse> history(@RequestParam(required = false) Long from,
                                         @RequestParam(required = false) Long to,
                                         @RequestParam(required = false) Integer hours) {
        return Mono.fromCallable(() -> {
            Window w = window(from, to, hours);
            List<TelemetrySample> samples = Downsampling.reduce(store.samplesBetween(w.from(), w.to()), MAX_POINTS);
            List<PowerChangeEvent> events = store.eventsBetween(w.from(), w.to());
            return new HistoryResponse(w.from(), w.to(), cfg.retentionDays(), cfg.recordIntervalMs(), samples, events);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Approximate miner energy (watt-hours) consumed over a window — the area under the miner's
     * power curve. Lightweight: returns only the number (no samples), so the UI can poll it cheaply
     * (e.g. "≈ kWh today" in the miner card). Same windowing as {@link #history}; the UI passes the
     * local-midnight → now range for "today".
     */
    @GetMapping("/energy")
    public Mono<EnergyResponse> energy(@RequestParam(required = false) Long from,
                                       @RequestParam(required = false) Long to,
                                       @RequestParam(required = false) Integer hours) {
        return Mono.fromCallable(() -> {
            Window w = window(from, to, hours);
            // Integrate the FULL (un-downsampled) samples for accuracy; don't integrate across a gap
            // longer than a few missed records (the app was down) so downtime can't inflate the total.
            double wh = MinerEnergy.approxConsumedWh(store.samplesBetween(w.from(), w.to()),
                    Duration.ofMillis(cfg.recordIntervalMs() * 4));
            return new EnergyResponse(w.from(), w.to(), wh);
        }).subscribeOn(Schedulers.boundedElastic()); // same reasoning as history() above
    }

    /** Resolve the [from, to] window, clamped to what we keep, guaranteeing {@code from < to}. */
    private Window window(Long from, Long to, Integer hours) {
        Instant now = Instant.now();
        Instant retentionStart = now.minus(cfg.retention());

        Instant toI = to != null ? Instant.ofEpochMilli(to) : now;
        if (toI.isAfter(now)) toI = now;                        // never beyond now
        if (toI.isBefore(retentionStart)) toI = retentionStart; // whole window off the end → empty-ish

        Instant fromI;
        if (from != null) {
            fromI = Instant.ofEpochMilli(from);
        } else {
            long h = hours != null ? Math.max(1, hours) : 12;   // default: last 12h
            fromI = toI.minus(Duration.ofHours(h));
        }
        if (fromI.isBefore(retentionStart)) fromI = retentionStart; // clamp to what we keep
        if (!fromI.isBefore(toI)) fromI = toI.minus(Duration.ofMinutes(1)); // guarantee from < to
        return new Window(fromI, toI);
    }

    private record Window(Instant from, Instant to) {}

    /** Just the miner energy for a window (watt-hours) — no samples, for cheap polling. */
    public record EnergyResponse(Instant from, Instant to, double minerEnergyWh) {}
}
