package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.PowerReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of the source hubs: hold the latest published reading, and {@code clear()} makes
 * {@code latest()} empty again (how an adapter signals "no live reading now" — a source outage —
 * so the engine promptly treats the surplus as unknown instead of serving a stale value).
 */
class SourceHubTest {

    private static final Instant T = Instant.parse("2026-07-27T12:00:00Z");

    @Test void solarHubHoldsLatestReadingAndClears() {
        var hub = new SolarSourceHub();
        assertThat(hub.latest()).isEmpty();
        hub.publish(new PowerReading(T, 4200));
        assertThat(hub.latest()).contains(new PowerReading(T, 4200));
        hub.clear();
        assertThat(hub.latest()).isEmpty();
    }

    @Test void consumptionHubHoldsLatestReadingAndClears() {
        var hub = new ConsumptionSourceHub();
        assertThat(hub.latest()).isEmpty();
        hub.publish(new PowerReading(T, 900));
        assertThat(hub.latest()).contains(new PowerReading(T, 900));
        hub.clear();
        assertThat(hub.latest()).isEmpty();
    }
}
