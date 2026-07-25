package io.dmitrykislov.miner.powersensor;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fans each new {@link HousePower} reading out to all connected SSE clients the
 * instant it arrives (no polling), and retains the last one so new subscribers
 * get an immediate value.
 */
@Service
public class HousePowerStreamService {

    private final Sinks.Many<HousePower> sink = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<HousePower> latest = new AtomicReference<>();

    public void publish(HousePower reading) {
        latest.set(reading);
        sink.tryEmitNext(reading);
    }

    public HousePower latest() {
        return latest.get();
    }

    /** Live stream; replays the last reading immediately if one exists. */
    public Flux<HousePower> stream() {
        HousePower seed = latest.get();
        Flux<HousePower> live = sink.asFlux();
        return seed == null ? live : Flux.concat(Flux.just(seed), live);
    }
}
