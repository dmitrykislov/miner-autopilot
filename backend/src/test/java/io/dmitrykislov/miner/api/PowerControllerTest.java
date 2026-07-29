package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.autopilot.ConsumptionSourceHub;
import io.dmitrykislov.miner.autopilot.SolarSourceHub;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.PowerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source-agnostic power feed reads only the SolarSource / ConsumptionSource ports. Uses the real
 * hubs (which correctly return Optional.empty when quiet) so the null/metered mapping is exercised.
 */
class PowerControllerTest {

    private SolarSourceHub solar;
    private ConsumptionSourceHub consumption;
    private PowerController controller;

    @BeforeEach
    void setup() {
        solar = new SolarSourceHub();
        consumption = new ConsumptionSourceHub();
        controller = new PowerController(solar, consumption);
    }

    @Test
    void latestReflectsBothPortsWhenReporting() {
        Instant sAt = Instant.parse("2026-07-27T02:00:00Z");
        Instant cAt = Instant.parse("2026-07-27T02:00:05Z");
        solar.publish(new PowerReading(sAt, 4200));
        consumption.publish(new PowerReading(cAt, 900));

        PowerSnapshot s = controller.latest();

        assertThat(s.solarW()).isEqualTo(4200.0);
        assertThat(s.solarAt()).isEqualTo(sAt);
        assertThat(s.consumptionW()).isEqualTo(900.0);
        assertThat(s.consumptionAt()).isEqualTo(cAt);
        assertThat(s.hasSolar()).isTrue();
        assertThat(s.metered()).isTrue();
    }

    @Test
    void quietPortsGiveNullsAndUnmetered() {
        PowerSnapshot s = controller.latest();

        assertThat(s.solarW()).isNull();
        assertThat(s.solarAt()).isNull();
        assertThat(s.consumptionW()).isNull();
        assertThat(s.consumptionAt()).isNull();
        assertThat(s.hasSolar()).isFalse();
        assertThat(s.metered()).isFalse();
    }

    @Test
    void solarPresentButConsumptionClearedIsHasSolarButUnmetered() {
        solar.publish(new PowerReading(Instant.parse("2026-07-27T02:00:00Z"), 3000));
        consumption.publish(new PowerReading(Instant.parse("2026-07-27T02:00:00Z"), 1000));
        consumption.clear(); // meter gated off / went stale

        PowerSnapshot s = controller.latest();

        assertThat(s.hasSolar()).isTrue();
        assertThat(s.solarW()).isEqualTo(3000.0);
        assertThat(s.metered()).isFalse();
        assertThat(s.consumptionW()).isNull();
    }

    @Test
    void streamEmitsTheCurrentSnapshotImmediately() {
        solar.publish(new PowerReading(Instant.parse("2026-07-27T02:00:00Z"), 2500));

        PowerSnapshot first = controller.stream().blockFirst(Duration.ofSeconds(3));

        assertThat(first).isNotNull();
        assertThat(first.solarW()).isEqualTo(2500.0);
        assertThat(first.metered()).isFalse();
    }
}
