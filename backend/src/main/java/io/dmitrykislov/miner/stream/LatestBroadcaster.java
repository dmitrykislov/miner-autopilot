package io.dmitrykislov.miner.stream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Fans the latest value of type {@code T} out to all connected SSE clients and
 * retains the most recent one so a new subscriber gets an immediate seed.
 *
 * <p>{@code directBestEffort}: broadcast to whoever is currently subscribed,
 * without buffering a backlog for late subscribers. New subscribers instead get
 * the last value immediately via the seed in {@link #stream()}, avoiding
 * duplicate delivery of buffered items.
 *
 * <p>Shared base for the inverter/house/miner stream services, which differ
 * only in the element type.
 */
public class LatestBroadcaster<T> {

    private final Sinks.Many<T> sink = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<T> latest = new AtomicReference<>();

    public void publish(T value) {
        latest.set(value);
        sink.tryEmitNext(value);
    }

    public T latest() {
        return latest.get();
    }

    /** Live stream; replays the last value immediately if one exists. */
    public Flux<T> stream() {
        T seed = latest.get();
        Flux<T> live = sink.asFlux();
        return seed == null ? live : Flux.concat(Flux.just(seed), live);
    }
}
