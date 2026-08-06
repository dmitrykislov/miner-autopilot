package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.MinerStatus;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Tests for the source-ports → {@link EnergyAverages} feeder: dedup-by-timestamp, source gating, draw. */
class EnergySamplerTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");
    /** The sampler's "now". Fixed relative to T0 so these tests do not depend on the real date. */
    private static final Instant NOW = T0.plusSeconds(300);

    // Core-only MinerStatusSource double (identical shape to the braiins MinerStreamService, but
    // without depending on the adapter module — the engine test must stay inside the engine layer).
    private static final class TestMinerStatusSource
            extends LatestBroadcaster<MinerStatus> implements MinerStatusSource {}

    private final SolarSourceHub solar = new SolarSourceHub();
    private final ConsumptionSourceHub consumption = new ConsumptionSourceHub();
    private final TestMinerStatusSource minerStream = new TestMinerStatusSource();
    private final EnergyAverages energy = new EnergyAverages(
            Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10),
            Duration.ZERO, Duration.ZERO);
    private final EnergySampler sampler = new EnergySampler(solar, consumption, minerStream, energy);

    private void emitSolar(Instant at, double watts) { solar.publish(new PowerReading(at, watts)); }
    private void emitConsumption(Instant at, double watts) { consumption.publish(new PowerReading(at, watts)); }

    private MinerStatus miner(String state, Integer drawW) {
        boolean mining = MinerStatus.MINING.equals(state);
        return new MinerStatus(true, mining, state, null, "S19k", drawW, true, mining ? 1 : 0, 1,
                null, drawW, List.of(), mining ? 600L : null, T0, null);
    }

    @Test void dedupesByTimestampSoAHeldReadingIsNotDoubleCounted() {
        emitSolar(T0, 2000);
        sampler.sample(NOW);
        sampler.sample(NOW); // same timestamp → must NOT re-record (would bias the mean)
        emitSolar(T0.plusSeconds(30), 4000);
        sampler.sample(NOW);

        // Two solar samples (2000, 4000) → mean 3000. If the held one were double-counted it'd be
        // (2000+2000+4000)/3 = 2666.7.
        assertThat(energy.signals(T0.plusSeconds(31)).solarShortW().getAsDouble()).isCloseTo(3000, within(1e-6));
    }

    @Test void recordsSolarButNotConsumptionWhenTheMeterIsSilent() {
        emitSolar(T0, 2000); // no consumption reading published
        sampler.sample(NOW);
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(2000, within(1e-6));
        assertThat(sig.consumptionShortW()).isEmpty();
        assertThat(sig.dataFresh()).isFalse(); // consumption feed never fed → not fresh
    }

    @Test void recordsNothingWhenNoSourceHasPublished() {
        sampler.sample(NOW); // neither source has emitted a reading (e.g. inverter offline / meter down)
        var sig = energy.signals(T0.plusSeconds(1));
        assertThat(sig.solarShortW()).isEmpty();
        assertThat(sig.consumptionShortW()).isEmpty();
    }

    @Test void recordsTheMinerDrawSoSurplusAddsItBack() {
        // solar 4 kW, house 3 kW (includes a 2 kW mining miner) → base 1 kW → surplus 3 kW.
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        emitSolar(T0, 4000);
        emitConsumption(T0, 3000);
        sampler.sample(NOW);
        assertThat(energy.signals(T0.plusSeconds(1)).shortSurplusW().getAsDouble())
                .isCloseTo(3000, within(1e-6)); // 4000−3000+2000
    }

    @Test void minerDrawCountsAsZeroWhenNotMining() {
        // Suspended (service up, ~0 W) → no draw added → surplus == solar − consumption.
        minerStream.publish(miner(MinerStatus.SUSPENDED, null));
        emitSolar(T0, 4000);
        emitConsumption(T0, 1000);
        sampler.sample(NOW);
        assertThat(energy.signals(T0.plusSeconds(1)).shortSurplusW().getAsDouble())
                .isCloseTo(3000, within(1e-6)); // 4000−1000+0
    }

    @Test void carriesLastKnownDrawThroughAMinerApiBlip() {
        // Rig mining at 2000 W (base 1000, house 3000) → surplus 3000.
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        emitSolar(T0, 4000);
        emitConsumption(T0, 3000);
        sampler.sample(NOW);
        // Miner status feed blips to OFFLINE, but the rig keeps drawing — consumption (independent
        // feed) still shows 3.0 kW. The draw must be CARRIED (2000), not zeroed.
        minerStream.publish(MinerStatus.offline(T0.plusSeconds(10), "blip"));
        emitSolar(T0.plusSeconds(10), 4000);
        emitConsumption(T0.plusSeconds(10), 3000);
        sampler.sample(NOW);

        // With draw carried, both samples → surplus 3000. If the blip sample recorded 0 W (the old
        // behaviour), avg(draw) would be 1000 → surplus 2000 (understated → could spuriously stop).
        assertThat(energy.signals(T0.plusSeconds(11)).shortSurplusW().getAsDouble())
                .isCloseTo(3000, within(1e-6));
    }

    @Test void carriesLastKnownDrawWhenMiningButTheDrawIsMomentarilyMissing() {
        // Rig mining at 2000 W (base 1000, house 3000) → surplus 3000.
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        emitSolar(T0, 4000);
        emitConsumption(T0, 3000);
        sampler.sample(NOW);
        // Still MINING, but the realtime stats momentarily omit the draw (powerDrawW == null). The
        // rig is still drawing ~2000, so the draw must be CARRIED, not zeroed.
        minerStream.publish(miner(MinerStatus.MINING, null));
        emitSolar(T0.plusSeconds(10), 4000);
        emitConsumption(T0.plusSeconds(10), 3000);
        sampler.sample(NOW);

        // Carried → both samples surplus 3000. If the null-draw sample recorded 0 W (the bug),
        // avg(draw) would be 1000 → surplus 2000 (understated → could spuriously stop the miner).
        assertThat(energy.signals(T0.plusSeconds(11)).shortSurplusW().getAsDouble())
                .isCloseTo(3000, within(1e-6));
    }

    @Test void decaysCarriedDrawToZeroOnSustainedUnreachability() {
        // A stopped miner reads as unreachable; carrying its old draw forever would over-state the
        // surplus. After the short carry window the draw must decay to 0 (miner assumed stopped).
        // True surplus is 3000 throughout (solar 4000, base 1000); the miner's own draw is internal.
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        emitSolar(T0, 4000);
        emitConsumption(T0, 3000); // house = base 1000 + draw 2000
        sampler.sample(NOW);
        minerStream.publish(MinerStatus.offline(T0, "down"));
        for (int i = 1; i <= 8; i++) { // sustained outage; house drops to base only
            emitSolar(T0.plusSeconds(10L * i), 4000);
            emitConsumption(T0.plusSeconds(10L * i), 1000);
            sampler.sample(NOW);
        }
        // A recent 40s window holds only decayed (draw=0) samples → surplus 3000, NOT 5000
        // (which an unbounded carry of the old 2000 W draw would have produced).
        assertThat(energy.surplusAvg(T0.plusSeconds(80), Duration.ofSeconds(40), Duration.ZERO).getAsDouble())
                .isCloseTo(3000, within(1e-6));
    }

    @Test void zeroesDrawWhenReachableAndNotMining() {
        // Mining at 2000, then reachable-but-STOPPED → draw is genuinely 0 (not carried).
        minerStream.publish(miner(MinerStatus.MINING, 2000));
        emitSolar(T0, 4000);
        emitConsumption(T0, 3000);
        sampler.sample(NOW);
        minerStream.publish(miner(MinerStatus.STOPPED, null));       // reachable, not mining
        emitSolar(T0.plusSeconds(10), 4000);
        emitConsumption(T0.plusSeconds(10), 1000);                   // house back to base 1000
        sampler.sample(NOW);
        // sample1 surplus 3000, sample2 surplus 3000 (4000−1000+0) → mean 3000; draw NOT carried.
        assertThat(energy.signals(T0.plusSeconds(11)).shortSurplusW().getAsDouble())
                .isCloseTo(3000, within(1e-6));
    }

    @Test void toleratesNoReadingsYet() {
        sampler.sample(NOW); // nothing published
        assertThat(energy.signals(T0).dataFresh()).isFalse();
    }
}
