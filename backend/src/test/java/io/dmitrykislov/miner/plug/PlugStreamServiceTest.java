package io.dmitrykislov.miner.plug;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PlugStreamServiceTest {

    private PlugStatus s(boolean on) {
        return new PlugStatus(true, on, "Plug", "P110", 12.3, 45.0, Instant.parse("2026-07-25T08:00:00Z"), null);
    }

    @Test
    void latestNullUntilFirstPublish() {
        assertThat(new PlugStreamService().latest()).isNull();
    }

    @Test
    void publishUpdatesLatest() {
        var svc = new PlugStreamService();
        var a = s(true);
        svc.publish(a);
        assertThat(svc.latest()).isSameAs(a);
    }

    @Test
    void streamReplaysLatestThenLive() {
        var svc = new PlugStreamService();
        var off = s(false); var on = s(true);
        svc.publish(off);
        StepVerifier.create(svc.stream())
                .expectNext(off)
                .then(() -> svc.publish(on))
                .expectNext(on)
                .thenCancel().verify(Duration.ofSeconds(3));
    }
}
