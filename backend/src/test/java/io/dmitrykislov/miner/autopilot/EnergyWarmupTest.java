package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.history.TelemetrySample;
import io.dmitrykislov.miner.history.TelemetryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests that {@link EnergyWarmup} seeds the averaging windows from persisted history on startup. */
class EnergyWarmupTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    private EnergyAverages freshEnergy() {
        // short 3 min, long 15 min, fresh-within 90 s, short-cov 60 s, long-cov 5 min.
        return new EnergyAverages(Duration.ofMinutes(3), Duration.ofMinutes(15),
                Duration.ofSeconds(90), Duration.ofSeconds(60), Duration.ofMinutes(5));
    }

    private HouseProperties props() {
        return new HouseProperties(null, null,
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),
                // all-zero → shipped defaults (longWindowMs = 900_000 = 15 min).
                new HouseProperties.Autopilot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void seedsWindowsSoSurplusIsAvailableImmediatelyAfterRestart() {
        var energy = freshEnergy();
        assertThat(energy.signals(NOW).shortSurplusW()).isEmpty(); // cold: no data yet

        // 15 stored samples over the last 14 min: solar 4000, house 1000, miner off → surplus 3000.
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 14; i >= 0; i--) {
            samples.add(new TelemetrySample(NOW.minusSeconds(60L * i), 4000.0, 1000.0, null, null, "OFF"));
        }
        var store = mock(TelemetryStore.class);
        when(store.samplesSince(any())).thenReturn(samples);

        new EnergyWarmup(store, energy, props()).warm(NOW);

        // Both windows are now covered and fresh → correct surplus available with no live samples yet.
        assertThat(energy.dataFresh(NOW)).isTrue();
        assertThat(energy.signals(NOW).shortSurplusW().getAsDouble()).isCloseTo(3000, within(1e-6));
        assertThat(energy.signals(NOW).longSurplusW().getAsDouble()).isCloseTo(3000, within(1e-6));
    }

    @Test
    void addsBackTheMinerDrawWhenReplayingAMiningSample() {
        var energy = freshEnergy();
        // House 3000 includes a 2000 W miner → base 1000 → surplus = 4000 − 3000 + 2000 = 3000.
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 14; i >= 0; i--) {
            samples.add(new TelemetrySample(NOW.minusSeconds(60L * i), 4000.0, 3000.0, 2000, 2000, "MINING"));
        }
        var store = mock(TelemetryStore.class);
        when(store.samplesSince(any())).thenReturn(samples);

        new EnergyWarmup(store, energy, props()).warm(NOW);

        assertThat(energy.signals(NOW).shortSurplusW().getAsDouble()).isCloseTo(3000, within(1e-6));
    }

    @Test
    void noOpOnEmptyHistory() {
        var energy = freshEnergy();
        var store = mock(TelemetryStore.class);
        when(store.samplesSince(any())).thenReturn(List.of());
        new EnergyWarmup(store, energy, props()).warm(NOW);
        assertThat(energy.signals(NOW).shortSurplusW()).isEmpty();
    }
}
