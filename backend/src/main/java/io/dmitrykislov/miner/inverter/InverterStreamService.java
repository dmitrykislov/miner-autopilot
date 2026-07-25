package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fans the latest {@link InverterSnapshot} out to all connected SSE clients and
 * retains the most recent one so new subscribers get an immediate value.
 */
@Service
public class InverterStreamService {

    // directBestEffort: broadcast to whoever is currently subscribed, without
    // buffering a backlog for late subscribers. New subscribers instead get the
    // last snapshot immediately via the seed in stream(), avoiding duplicate
    // delivery of buffered items.
    private final Sinks.Many<InverterSnapshot> sink =
            Sinks.many().multicast().directBestEffort();

    private final AtomicReference<InverterSnapshot> latest = new AtomicReference<>();

    public void publish(InverterSnapshot snapshot) {
        latest.set(snapshot);
        sink.tryEmitNext(snapshot);
    }

    public InverterSnapshot latest() {
        return latest.get();
    }

    /** Live stream; replays the last snapshot immediately if one exists. */
    public Flux<InverterSnapshot> stream() {
        InverterSnapshot seed = latest.get();
        Flux<InverterSnapshot> live = sink.asFlux();
        return seed == null ? live : Flux.concat(Flux.just(seed), live);
    }
}
