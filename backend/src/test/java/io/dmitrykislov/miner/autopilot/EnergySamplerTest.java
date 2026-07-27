package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Tests for the snapshot → {@link EnergyAverages} feeder: dedup-by-timestamp, metered gating, draw. */
class EnergySamplerTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");

    private final InverterStreamService stream = new InverterStreamService();
    private final MinerStreamService minerStream = new MinerStreamService();
    private final EnergyAverages energy = new EnergyAverages(
            Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10),
            Duration.ZERO, Duration.ZERO);
    private final EnergySampler sampler = new EnergySampler(stream, minerStream, energy);

    private InverterSnapshot snap(boolean online, PowerBalance pb, Instant ts) {
        return new InverterSnapshot(online, "SG10RS", "SN", online ? "Running" : "Offline", ts,
                Map.of(), pb, List.of(), List.of(), null);
    }

    private MinerStatus miner(String state, Integer drawW) {
        boolean mining = MinerStatus.MINING.equals(state);
        return new MinerStatus(true, mining, state, null, "S19k", drawW, true, mining ? 1 : 0, 1,
                null, drawW, List.of(), mining ? 600L : null, T0, null);
    }

    @Test void dedupesByTimestampSoAHeldSnapshotIsNotDoubleCounted() {
        stream.publish(snap(true, PowerBalance.metered(2.0, 1.0), T0));
        sampler.sample();
        sampler.sample(); // same timestamp → must NOT re-record (would bias the mean)
        stream.publish(snap(true, PowerBalance.metered(4.0, 1.0), T0.plusSeconds(30)));
        sampler.sample();

        var sig = energy.signals(T0.plusSeconds(31));
        // Two solar samples (2000, 4000) → mean 3000. If the held one were double-counted it'd be
        // (2000+2000+4000)/3 = 2666.7.
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(3000, within(1e-6));
    }

    @Test void recordsSolarButNotConsumptionWhenUnmetered() {
        stream.publish(snap(true, PowerBalance.unmetered(2.0), T0));
        sampler.sample();
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(2000, within(1e-6));
        assertThat(sig.consumptionShortW()).isEmpty();
        assertThat(sig.dataFresh()).isFalse(); // consumption feed never fed → not fresh
    }

    @Test void recordsNothingWhenOffline() {
        stream.publish(InverterSnapshot.offline("SG10RS", "SN", T0, "poll failed"));
        sampler.sample();
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.solarShortW()).isEmpty();
        assertThat(sig.consumptionShortW()).isEmpty();
    }

    @Test void recordsTheMinerDrawSoSurplusAddsItBack() {
        // solar 4 kW, house 3 kW (includes a 2 kW mining miner) → base 1 kW → surplus 3 kW.
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        stream.publish(snap(true, PowerBalance.metered(4.0, 3.0), T0));
        sampler.sample();
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.shortSurplusW().getAsDouble()).isCloseTo(3000, within(1e-6)); // 4000−3000+2000
    }

    @Test void minerDrawCountsAsZeroWhenNotMining() {
        // Suspended (service up, ~0 W) → no draw added → surplus == solar − consumption.
        minerStream.publish(miner(MinerStatus.SUSPENDED, null));
        stream.publish(snap(true, PowerBalance.metered(4.0, 1.0), T0));
        sampler.sample();
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.shortSurplusW().getAsDouble()).isCloseTo(3000, within(1e-6)); // 4000−1000+0
    }

    @Test void toleratesNoSnapshotYet() {
        sampler.sample(); // nothing published
        assertThat(energy.signals(T0).dataFresh()).isFalse();
    }
}
