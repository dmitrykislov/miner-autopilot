package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerSnapshot;
import io.dmitrykislov.miner.port.SolarSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Source-agnostic live power feed for the UI, read straight from the {@link SolarSource} and
 * {@link ConsumptionSource} ports:
 * <ul>
 *   <li>{@code GET /api/power/stream} — SSE feed of {@link PowerSnapshot}s (emits on change)</li>
 *   <li>{@code GET /api/power/latest} — the current snapshot (one-shot)</li>
 * </ul>
 *
 * <p>Because it depends only on the ports — not on any specific inverter/meter — the dashboard's live
 * flow works with whatever source feeds those ports (Sungrow + Solar Analytics, the HTTP ingest
 * endpoint, or a custom adapter). Device-specific detail lives on the adapter's own endpoint
 * (e.g. {@code /api/inverter}); this carries only the common denominator, watts.
 *
 * <p>Implementation note: rather than a shared broadcaster fed by a scheduled task, each subscriber
 * simply polls the ports on a short interval and {@code distinctUntilChanged()} collapses unchanged
 * ticks — so a snapshot is pushed only when a source actually publishes (or clears) a reading. The
 * port reads are cheap in-memory {@code AtomicReference} loads, and polling the port (not a hub's
 * push stream) is what keeps this working for <em>every</em> adapter.
 */
@RestController
@RequestMapping("/api/power")
@CrossOrigin
public class PowerController {

    /** How often each subscriber samples the ports; latency-bound only (distinctUntilChanged dedups). */
    static final Duration POLL = Duration.ofSeconds(2);

    private final SolarSource solar;
    private final ConsumptionSource consumption;

    public PowerController(SolarSource solar, ConsumptionSource consumption) {
        this.solar = solar;
        this.consumption = consumption;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PowerSnapshot> stream() {
        Flux<PowerSnapshot> live = Flux.interval(Duration.ZERO, POLL)
                .map(t -> snapshot())
                .distinctUntilChanged();
        return Sse.withHeartbeat(live, this::snapshot);
    }

    @GetMapping("/latest")
    public PowerSnapshot latest() {
        return snapshot();
    }

    private PowerSnapshot snapshot() {
        return PowerSnapshot.of(solar.latest().orElse(null), consumption.latest().orElse(null));
    }
}
