package io.dmitrykislov.miner.braiins;
import io.dmitrykislov.miner.port.MinerStatus;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MinerStreamServiceTest {

    private MinerStatus s(boolean running) {
        return new MinerStatus(true, running, running ? "MINING" : "STOPPED", null, "S19k", 1200, true,
                running ? 1 : 0, 1, running ? 95.0 : null, running ? 1150 : null, java.util.List.of(),
                running ? 3600L : null, Instant.parse("2026-07-25T08:00:00Z"), null);
    }

    @Test
    void latestNullUntilPublish() {
        assertThat(new MinerStreamService().latest()).isNull();
    }

    @Test
    void streamReplaysLatestThenLive() {
        var svc = new MinerStreamService();
        var stopped = s(false);
        var running = s(true);
        svc.publish(stopped);
        StepVerifier.create(svc.stream())
                .expectNext(stopped)
                .then(() -> svc.publish(running))
                .expectNext(running)
                .thenCancel().verify(Duration.ofSeconds(3));
    }
}
