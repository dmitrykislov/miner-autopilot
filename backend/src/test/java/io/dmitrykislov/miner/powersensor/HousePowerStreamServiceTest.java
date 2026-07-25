package io.dmitrykislov.miner.powersensor;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HousePowerStreamServiceTest {

    private HousePower w(double watts) {
        return HousePower.measured(watts, 241.0, "clamp", Instant.parse("2026-07-25T08:00:00Z"));
    }

    @Test
    void latestNullUntilFirstPublish() {
        assertThat(new HousePowerStreamService().latest()).isNull();
    }

    @Test
    void publishUpdatesLatest() {
        var svc = new HousePowerStreamService();
        var r = w(500);
        svc.publish(r);
        assertThat(svc.latest()).isSameAs(r);
    }

    @Test
    void streamReplaysLatestThenDeliversLiveUpdates() {
        var svc = new HousePowerStreamService();
        var r1 = w(500);
        var r2 = w(900);
        svc.publish(r1);

        StepVerifier.create(svc.stream())
                .expectNext(r1)
                .then(() -> svc.publish(r2))
                .expectNext(r2)
                .thenCancel()
                .verify(Duration.ofSeconds(3));
    }
}
