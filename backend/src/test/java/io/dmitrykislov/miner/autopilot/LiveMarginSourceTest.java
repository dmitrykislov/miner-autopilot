package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.config.HouseProperties;
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

/**
 * Verifies the live margin is netSurplus (solar − house) in watts, and empty when
 * unusable — no snapshot, offline, unmetered consumption, or a stale snapshot.
 * Inverter poll interval is 10 s here, so the freshness window is 40 s.
 */
class LiveMarginSourceTest {

    private final InverterStreamService stream = mock(InverterStreamService.class);
    private final HouseProperties props = new HouseProperties(
            new HouseProperties.Inverter("h", 443, "/ws", "u", "p", 10_000, 8_000),
            null, null, null);
    private final LiveMarginSource source = new LiveMarginSource(stream, props);

    private InverterSnapshot online(double solarKw, double houseKw, Instant when) {
        return new InverterSnapshot(true, "SG10RS", "SN", "Running", when,
                Map.of(), PowerBalance.metered(solarKw, houseKw), List.of(), List.of(), null);
    }

    private InverterSnapshot online(double solarKw, double houseKw) {
        return online(solarKw, houseKw, Instant.now());
    }

    @Test void marginIsExportSurplusInWatts() {
        when(stream.latest()).thenReturn(online(3.0, 1.5));      // solar 3 − house 1.5 ⇒ +1500 W surplus
        assertThat(source.currentMarginWatts()).isPresent();
        assertThat(source.currentMarginWatts().getAsDouble()).isCloseTo(1500.0, within(1e-6));
    }

    @Test void marginCanBeNegative() {
        when(stream.latest()).thenReturn(online(0.2, 0.9));      // solar 0.2 − house 0.9 ⇒ −700 W
        assertThat(source.currentMarginWatts().getAsDouble()).isCloseTo(-700.0, within(1e-6));
    }

    @Test void emptyWhenNoSnapshotYet() {
        when(stream.latest()).thenReturn(null);
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void emptyWhenInverterOffline() {
        when(stream.latest()).thenReturn(
                InverterSnapshot.offline("SG10RS", "SN", Instant.now(), "boom"));
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void emptyWhenConsumptionNotMetered() {
        // online, but Solar Analytics isn't reporting (unmetered) → margin unavailable
        var snap = new InverterSnapshot(true, "SG10RS", "SN", "Running", Instant.now(),
                Map.of(), PowerBalance.unmetered(3.0), List.of(), List.of(), null);
        when(stream.latest()).thenReturn(snap);
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void emptyWhenSnapshotStale() {
        // A stalled poller keeps the last online snapshot around; once it ages past the
        // 40 s freshness window the margin must go unknown so the autopilot stops safely.
        when(stream.latest()).thenReturn(online(3.0, 1.5, Instant.now().minusSeconds(120)));
        assertThat(source.currentMarginWatts()).isEmpty();
    }

    @Test void freshWithinWindowIsStillUsable() {
        // Just inside the window (30 s < 40 s) → still a valid, usable margin.
        when(stream.latest()).thenReturn(online(3.0, 1.5, Instant.now().minusSeconds(30)));
        assertThat(source.currentMarginWatts()).isPresent();
        assertThat(source.currentMarginWatts().getAsDouble()).isCloseTo(1500.0, within(1e-6));
    }

    @Test void emptyWhenTimestampMissing() {
        var snap = new InverterSnapshot(true, "SG10RS", "SN", "Running", null,
                Map.of(), PowerBalance.metered(3.0, 1.5), List.of(), List.of(), null);
        when(stream.latest()).thenReturn(snap);
        assertThat(source.currentMarginWatts()).isEmpty();
    }
}
