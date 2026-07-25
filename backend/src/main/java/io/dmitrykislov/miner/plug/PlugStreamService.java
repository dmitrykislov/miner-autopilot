package io.dmitrykislov.miner.plug;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/** Broadcasts {@link PlugStatus} updates to SSE clients and retains the last one. */
@Service
public class PlugStreamService {

    private final Sinks.Many<PlugStatus> sink = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<PlugStatus> latest = new AtomicReference<>();

    public void publish(PlugStatus status) {
        latest.set(status);
        sink.tryEmitNext(status);
    }

    public PlugStatus latest() {
        return latest.get();
    }

    public Flux<PlugStatus> stream() {
        PlugStatus seed = latest.get();
        Flux<PlugStatus> live = sink.asFlux();
        return seed == null ? live : Flux.concat(Flux.just(seed), live);
    }
}
