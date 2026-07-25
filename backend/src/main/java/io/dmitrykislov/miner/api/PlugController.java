package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.plug.PlugService;
import io.dmitrykislov.miner.plug.PlugStatus;
import io.dmitrykislov.miner.plug.PlugStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * Control + live status for the Tapo P110 smart plug:
 * <ul>
 *   <li>{@code GET  /api/plug/stream} — SSE feed of {@link PlugStatus}</li>
 *   <li>{@code GET  /api/plug/status} — most recent status (one-shot)</li>
 *   <li>{@code POST /api/plug/on|off|toggle} — switch the relay, returns new status</li>
 * </ul>
 *
 * <p>The KLAP calls are blocking, so commands run on a bounded-elastic scheduler
 * to keep the reactive event loop free.
 */
@RestController
@RequestMapping("/api/plug")
@CrossOrigin
public class PlugController {

    private final PlugService plug;
    private final PlugStreamService stream;

    public PlugController(PlugService plug, PlugStreamService stream) {
        this.plug = plug;
        this.stream = stream;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlugStatus> stream() {
        return Sse.withHeartbeat(stream.stream(), stream::latest);
    }

    @GetMapping("/status")
    public PlugStatus status() {
        return stream.latest();
    }

    @PostMapping("/on")
    public Mono<PlugStatus> on() {
        return Mono.fromCallable(() -> plug.setOn(true)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/off")
    public Mono<PlugStatus> off() {
        return Mono.fromCallable(() -> plug.setOn(false)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/toggle")
    public Mono<PlugStatus> toggle() {
        return Mono.fromCallable(plug::toggle).subscribeOn(Schedulers.boundedElastic());
    }
}
