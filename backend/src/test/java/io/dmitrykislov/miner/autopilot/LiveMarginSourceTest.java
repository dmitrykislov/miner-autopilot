package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the live margin is netSurplus (solar − house) in watts, and empty when unusable. */
class LiveMarginSourceTest {

    private final InverterStreamService stream = mock(InverterStreamService.class);
    private final LiveMarginSource source = new LiveMarginSource(stream);

    private InverterSnapshot online(double solarKw, double houseKw) {
        return new InverterSnapshot(true, "SG10RS", "SN", "Running", Instant.now(),
                Map.of(), PowerBalance.of(solarKw, houseKw, true), List.of(), List.of(), null);
    }

    @Test void marginIsNetSurplusInWatts() {
        when(stream.latest()).thenReturn(online(3.0, 1.5));      // +1.5 kW surplus
        assertThat(source.currentMarginWatts()).isPresent();
        assertThat(source.currentMarginWatts().getAsDouble()).isCloseTo(1500.0, within(1e-6));
    }

    @Test void marginCanBeNegative() {
        when(stream.latest()).thenReturn(online(0.2, 0.9));      // importing 0.7 kW
        assertThat(source.currentMarginWatts().getAsDouble()).isCloseTo(-700.0, within(1e-6));
    }

    @Test void emptyWhenNoSnapshotYet() {
        when(stream.latest()).thenReturn(null);
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void emptyWhenInverterOffline() {
        when(stream.latest()).thenReturn(
                InverterSnapshot.offline("SG10RS", "SN", Instant.now(), 0.5, "boom"));
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void emptyWhenConsumptionNotMetered() {
        // online, but house load is the assumed baseline (not measured) → don't act on it
        var snap = new InverterSnapshot(true, "SG10RS", "SN", "Running", Instant.now(),
                Map.of(), PowerBalance.of(3.0, 0.5, false), List.of(), List.of(), null);
        when(stream.latest()).thenReturn(snap);
        assertThat(source.currentMarginWatts()).isEmpty();
    }
}
