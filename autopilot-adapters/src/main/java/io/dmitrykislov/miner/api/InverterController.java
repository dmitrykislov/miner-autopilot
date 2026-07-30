package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * HTTP surface for the UI:
 * <ul>
 *   <li>{@code GET /api/inverter/stream} — live SSE feed of {@link InverterSnapshot}s</li>
 *   <li>{@code GET /api/inverter/latest} — most recent snapshot (one-shot)</li>
 * </ul>
 * House consumption comes from Solar Analytics (see {@code /api/house}); the
 * inverter only supplies solar generation, so there is no house-load endpoint.
 */
@RestController
@RequestMapping("/api/inverter")
public class InverterController {

    private final InverterStreamService stream;

    public InverterController(InverterStreamService stream) {
        this.stream = stream;
    }

    /** Server-Sent Events stream of live snapshots (one event per poll). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<InverterSnapshot> stream() {
        return Sse.withHeartbeat(stream.stream(), stream::latest);
    }

    @GetMapping("/latest")
    public InverterSnapshot latest() {
        return stream.latest();
    }
}
