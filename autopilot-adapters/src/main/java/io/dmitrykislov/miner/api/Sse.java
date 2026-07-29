package io.dmitrykislov.miner.api;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared Server-Sent-Events helpers. Every device stream is the live feed merged
 * with a periodic heartbeat that re-emits the last value, so idle connections and
 * intermediaries don't drop the stream between real updates.
 */
final class Sse {

    /** How often to re-emit the latest value to keep an idle SSE connection alive. */
    static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private Sse() {}

    static <T> Flux<T> withHeartbeat(Flux<T> live, Supplier<T> latest) {
        Flux<T> heartbeat = Flux.interval(HEARTBEAT).map(t -> latest.get()).filter(Objects::nonNull);
        return Flux.merge(live, heartbeat);
    }
}
