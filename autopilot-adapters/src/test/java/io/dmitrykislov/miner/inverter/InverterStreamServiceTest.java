package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InverterStreamServiceTest {

    private InverterSnapshot snap(String model) {
        return InverterSnapshot.offline(model, "SN", Instant.parse("2026-07-25T08:00:00Z"), null);
    }

    @Test
    void latestIsNullUntilFirstPublish() {
        assertThat(new InverterStreamService().latest()).isNull();
    }

    @Test
    void publishUpdatesLatest() {
        var svc = new InverterStreamService();
        var s = snap("SG10RS");
        svc.publish(s);
        assertThat(svc.latest()).isSameAs(s);
    }

    @Test
    void streamReplaysLatestThenDeliversLiveUpdates() {
        var svc = new InverterStreamService();
        var s1 = snap("first");
        var s2 = snap("second");
        svc.publish(s1);

        StepVerifier.create(svc.stream())
                .expectNext(s1)                       // seed = last snapshot
                .then(() -> svc.publish(s2))
                .expectNext(s2)                       // live update
                .thenCancel()
                .verify(java.time.Duration.ofSeconds(3));
    }
}
