package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.port.MinerDriver;
import io.dmitrykislov.miner.port.MinerStatusSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


/**
 * Control + live status for the Braiins OS+ miner:
 * <ul>
 *   <li>{@code GET  /api/miner/stream} — SSE feed of {@link MinerStatus}</li>
 *   <li>{@code GET  /api/miner/status} — most recent status (one-shot)</li>
 *   <li>{@code POST /api/miner/start|stop} — start/stop mining</li>
 *   <li>{@code POST /api/miner/power?watts=&apply=} — set autotuning power target</li>
 * </ul>
 * GraphQL calls are blocking, so commands run on a bounded-elastic scheduler.
 */
@RestController
@RequestMapping("/api/miner")
@CrossOrigin
public class MinerController {

    private final MinerDriver miner;
    private final MinerStatusSource stream;

    public MinerController(MinerDriver miner, MinerStatusSource stream) {
        this.miner = miner;
        this.stream = stream;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<MinerStatus> stream() {
        return Sse.withHeartbeat(stream.stream(), stream::latest);
    }

    @GetMapping("/status")
    public MinerStatus status() {
        return stream.latest();
    }

    @PostMapping("/start")
    public Mono<MinerStatus> start() {
        return Mono.fromCallable(miner::start).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/stop")
    public Mono<MinerStatus> stop() {
        return Mono.fromCallable(miner::stop).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/power")
    public Mono<MinerStatus> power(@RequestParam int watts,
                                   @RequestParam(defaultValue = "true") boolean apply) {
        return Mono.fromCallable(() -> miner.setPowerTarget(watts, apply))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
