package io.dmitrykislov.miner.braiins;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/** Broadcasts {@link MinerStatus} updates to SSE clients and retains the last one. */
@Service
public class MinerStreamService {

    private final Sinks.Many<MinerStatus> sink = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<MinerStatus> latest = new AtomicReference<>();

    public void publish(MinerStatus status) {
        latest.set(status);
        sink.tryEmitNext(status);
    }

    public MinerStatus latest() {
        return latest.get();
    }

    public Flux<MinerStatus> stream() {
        MinerStatus seed = latest.get();
        Flux<MinerStatus> live = sink.asFlux();
        return seed == null ? live : Flux.concat(Flux.just(seed), live);
    }
}
