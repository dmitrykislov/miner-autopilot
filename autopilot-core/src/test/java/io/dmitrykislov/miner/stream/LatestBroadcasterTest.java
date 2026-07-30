package io.dmitrykislov.miner.stream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the shared SSE fan-out. This sits under every live stream in the app (inverter, house,
 * miner, autopilot), so its guarantees matter: a new subscriber must see the current value at once,
 * a slow subscriber must not accumulate a backlog on the Pi's small heap, and concurrent publishers
 * must not lose emissions.
 */
class LatestBroadcasterTest {

    @Test void latestIsNullUntilSomethingIsPublished() {
        assertThat(new LatestBroadcaster<String>().latest()).isNull();
    }

    @Test void latestReturnsTheMostRecentValue() {
        var b = new LatestBroadcaster<String>();
        b.publish("first");
        b.publish("second");
        assertThat(b.latest()).isEqualTo("second");
    }

    @Test void aNewSubscriberIsSeededWithTheCurrentValue() {
        var b = new LatestBroadcaster<String>();
        b.publish("seed");
        // Subscribing after the fact must still yield the current value immediately — this is what
        // makes a freshly opened dashboard show data without waiting for the next poll.
        StepVerifier.create(b.stream())
                .expectNext("seed")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test void aSubscriberWithNoSeedSimplyWaitsForTheNextValue() {
        var b = new LatestBroadcaster<String>();
        StepVerifier.create(b.stream())
                .expectSubscription()                    // onSubscribe counts as an event to expectNoEvent
                .expectNoEvent(Duration.ofMillis(50))
                .then(() -> b.publish("live"))
                .expectNext("live")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test void theSeedIsNotDeliveredTwiceWhenAValueIsAlsoPublishedLive() {
        var b = new LatestBroadcaster<String>();
        b.publish("seed");
        StepVerifier.create(b.stream())
                .expectNext("seed")                      // from the seed
                .then(() -> b.publish("next"))
                .expectNext("next")                      // from the sink, not a replay of "seed"
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test void everySubscriberReceivesEachPublishedValue() {
        var b = new LatestBroadcaster<Integer>();
        Flux<Integer> a = b.stream();
        Flux<Integer> c = b.stream();

        StepVerifier.create(Flux.zip(a, c, (x, y) -> x + ":" + y))
                .then(() -> b.publish(1))
                .expectNext("1:1")
                .then(() -> b.publish(2))
                .expectNext("2:2")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test void aStreamNeverTerminatesOnItsOwn() {
        // A device outage must not complete or error the stream — the browser's EventSource would
        // then have to reconnect for no reason. Nothing here ever calls complete()/error().
        var b = new LatestBroadcaster<String>();
        b.publish("one");
        StepVerifier.create(b.stream())
                .expectNext("one")
                .expectNoEvent(Duration.ofMillis(100))   // still open, just quiet
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test void publishWithNoSubscribersIsHarmlessAndStillRecordsLatest() {
        var b = new LatestBroadcaster<String>();
        b.publish("nobody-listening");                    // tryEmitNext returns a "no subscriber" result
        assertThat(b.latest()).isEqualTo("nobody-listening");
        // …and a later subscriber is seeded with it.
        StepVerifier.create(b.stream()).expectNext("nobody-listening").thenCancel().verify(Duration.ofSeconds(5));
    }

    @Test void concurrentPublishersDoNotLoseTheLatestValue() throws Exception {
        // publish() is synchronized because directBestEffort requires serialized emitNext: the miner's
        // scheduled poll and a user start/stop command publish from different threads. Without it,
        // emissions fail with FAIL_NON_SERIALIZED. Assert the final state is one of the published
        // values and that nothing throws under contention.
        var b = new LatestBroadcaster<Integer>();
        int threads = 8, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var errors = new CopyOnWriteArrayList<Throwable>();
        var start = new CountDownLatch(1);
        try {
            List<Integer> ids = IntStream.range(0, threads).boxed().toList();
            for (int t : ids) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) b.publish(t * perThread + i);
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).isEmpty();
        assertThat(b.latest()).isNotNull();
        assertThat(b.latest()).isBetween(0, threads * perThread);
    }

    @Test void aSlowSubscriberDoesNotStallOrBacklogTheOthers() {
        // directBestEffort drops for a subscriber that isn't requesting rather than buffering — that is
        // what stops one stalled browser tab growing the heap. Here a subscriber that requests nothing
        // must not prevent a healthy one from receiving values.
        var b = new LatestBroadcaster<Integer>();
        Flux<Integer> healthy = b.stream();

        StepVerifier.create(b.stream(), 0)               // subscribe but request NOTHING
                .then(() -> StepVerifier.create(healthy)
                        .then(() -> b.publish(1))
                        .expectNext(1)
                        .thenCancel()
                        .verify(Duration.ofSeconds(5)))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
