package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.port.TelemetrySample;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Area-under-the-miner-curve = ENERGY (watt-hours). Trapezoidal integration of the miner's power
 * (draw, falling back to target, else 0) over time, with over-large gaps not integrated across.
 */
class MinerEnergyTest {

    private static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");
    private static final Duration GAP_5M = Duration.ofMinutes(5);

    /** Sample at T0+sec with the given draw / target (watts); null → absent. */
    private static TelemetrySample s(long sec, Integer drawW, Integer powerW) {
        String state = (drawW != null || powerW != null) ? "MINING" : "OFF";
        return new TelemetrySample(T0.plusSeconds(sec), null, null, powerW, drawW, state);
    }

    @Test void emptyOrSingleSampleIsZero() {
        assertThat(MinerEnergy.approxConsumedWh(List.of(), GAP_5M)).isZero();
        assertThat(MinerEnergy.approxConsumedWh(null, GAP_5M)).isZero();
        assertThat(MinerEnergy.approxConsumedWh(List.of(s(0, 2000, null)), GAP_5M)).isZero();
    }

    @Test void constantDrawOverOneStep() {
        // 2000 W for 60 s = 2000 × (60/3600) = 33.33 Wh
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, 2000, null), s(60, 2000, null)), GAP_5M);
        assertThat(wh).isCloseTo(33.333, within(0.01));
    }

    @Test void constantDrawOverAnHour() {
        // 2000 W held for a full hour (two half-hour steps) = 2000 Wh = 2 kWh
        double wh = MinerEnergy.approxConsumedWh(
                List.of(s(0, 2000, null), s(1800, 2000, null), s(3600, 2000, null)), Duration.ofHours(1));
        assertThat(wh).isCloseTo(2000.0, within(1e-6));
    }

    @Test void linearRampIsTheTrapezoidArea() {
        // 0 → 2000 W linearly over 1 h = average 1000 W × 1 h = 1000 Wh
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, 0, null), s(3600, 2000, null)), Duration.ofHours(2));
        assertThat(wh).isCloseTo(1000.0, within(1e-6));
    }

    @Test void offSamplesContributeZero() {
        assertThat(MinerEnergy.approxConsumedWh(List.of(s(0, 0, null), s(60, 0, null)), GAP_5M)).isZero();
        // both draw and target null (miner off) → 0 W → 0 Wh
        assertThat(MinerEnergy.approxConsumedWh(List.of(s(0, null, null), s(60, null, null)), GAP_5M)).isZero();
    }

    @Test void fallsBackToTargetWhenDrawMissingWhileMining() {
        // draw null but target 2000 → uses 2000 → 33.33 Wh over 60 s
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, null, 2000), s(60, null, 2000)), GAP_5M);
        assertThat(wh).isCloseTo(33.333, within(0.01));
    }

    @Test void drawIsPreferredOverTarget() {
        // draw 1800 with target 2000 → integrates the actual draw (1800), not the target
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, 1800, 2000), s(60, 1800, 2000)), GAP_5M);
        assertThat(wh).isCloseTo(30.0, within(0.01)); // 1800 × 60/3600
    }

    @Test void aGapLargerThanMaxIsNotIntegratedAcross() {
        // 10-min gap with a 5-min cap → the app was down; don't invent energy for it.
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, 2000, null), s(600, 2000, null)), GAP_5M);
        assertThat(wh).isZero();
    }

    @Test void mixedOnOffOnIntegratesEachStep() {
        // 0→2000 (16.67) + 2000→2000 (33.33) + 2000→0 (16.67) = 66.67 Wh
        double wh = MinerEnergy.approxConsumedWh(
                List.of(s(0, 0, null), s(60, 2000, null), s(120, 2000, null), s(180, 0, null)), GAP_5M);
        assertThat(wh).isCloseTo(66.667, within(0.01));
    }

    @Test void outOfOrderOrDuplicateTimestampsAddNothing() {
        // duplicate timestamp (dt == 0) → skipped
        assertThat(MinerEnergy.approxConsumedWh(List.of(s(0, 2000, null), s(0, 2000, null)), GAP_5M)).isZero();
        // out-of-order (dt < 0) → skipped, never negative
        assertThat(MinerEnergy.approxConsumedWh(List.of(s(100, 2000, null), s(50, 2000, null)), GAP_5M)).isZero();
    }

    @Test void nullTimestampStepsAreSkipped() {
        var withNull = new TelemetrySample(null, null, null, null, 2000, "MINING");
        double wh = MinerEnergy.approxConsumedWh(List.of(s(0, 2000, null), withNull, s(120, 2000, null)), GAP_5M);
        assertThat(wh).isZero(); // both steps touch a null timestamp → skipped, no NPE
    }

    @Test void realisticDayApproximatesTheExpectedKwh() {
        // A coarse "clear day": ramp up 0→3000 over 2 h, hold 3000 for 4 h, ramp down 3000→0 over 2 h.
        // Areas: 3000 Wh (up) + 12000 Wh (hold) + 3000 Wh (down) = 18000 Wh = 18 kWh.
        long h = 3600;
        double wh = MinerEnergy.approxConsumedWh(List.of(
                s(0, 0, null), s(2 * h, 3000, null), s(6 * h, 3000, null), s(8 * h, 0, null)),
                Duration.ofHours(5)); // maxGap comfortably above the 2 h steps
        assertThat(wh / 1000.0).isCloseTo(18.0, within(1e-6));
    }
}
