package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.inverter.HouseLoadState;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * HTTP surface for the UI:
 * <ul>
 *   <li>{@code GET /api/inverter/stream} — live SSE feed of {@link InverterSnapshot}s</li>
 *   <li>{@code GET /api/inverter/latest} — most recent snapshot (one-shot)</li>
 *   <li>{@code GET /api/inverter/house-load} — current assumed house consumption</li>
 *   <li>{@code POST /api/inverter/house-load?kw=} — adjust it (drives the backend margin)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/inverter")
@CrossOrigin // allow the Vite dev server (different origin) during development
public class InverterController {

    private final InverterStreamService stream;
    private final HouseLoadState houseLoad;

    public InverterController(InverterStreamService stream, HouseLoadState houseLoad) {
        this.stream = stream;
        this.houseLoad = houseLoad;
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

    @GetMapping("/house-load")
    public Map<String, Object> houseLoad() {
        return Map.of("houseLoadKw", houseLoad.get(),
                "metered", false,
                "note", "SG10RS has no energy meter; this is an assumed baseline.");
    }

    @PostMapping("/house-load")
    public Map<String, Object> setHouseLoad(@RequestParam double kw) {
        houseLoad.set(kw);
        return Map.of("houseLoadKw", houseLoad.get());
    }
}
