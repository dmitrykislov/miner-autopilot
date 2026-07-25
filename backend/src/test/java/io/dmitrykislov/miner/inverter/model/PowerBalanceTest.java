package io.dmitrykislov.miner.inverter.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PowerBalanceTest {

    @Test
    void surplusWhenSolarExceedsHouse() {
        var b = PowerBalance.metered(3.0, 1.0);
        assertThat(b.solarPowerKw()).isEqualTo(3.0);
        assertThat(b.houseConsumptionKw()).isEqualTo(1.0);
        assertThat(b.netSurplusKw()).isEqualTo(2.0);
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.solarCoveragePct()).isEqualTo(100.0); // capped
        assertThat(b.consumptionMetered()).isTrue();
    }

    @Test
    void deficitWhenHouseExceedsSolar() {
        var b = PowerBalance.metered(1.0, 4.0);
        assertThat(b.netSurplusKw()).isEqualTo(-3.0);
        assertThat(b.coveringLoad()).isFalse();
        assertThat(b.solarCoveragePct()).isEqualTo(25.0);
    }

    @Test
    void exactlyCoveringCountsAsCovering() {
        var b = PowerBalance.metered(2.0, 2.0);
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.netSurplusKw()).isEqualTo(0.0);
        assertThat(b.solarCoveragePct()).isEqualTo(100.0);
    }

    @Test
    void zeroHouseLoadGivesFullCoverage() {
        var b = PowerBalance.metered(0.0, 0.0);
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.solarCoveragePct()).isEqualTo(100.0);
        assertThat(b.netSurplusKw()).isEqualTo(0.0);
    }

    @Test
    void valuesAreRoundedToMilliUnits() {
        var b = PowerBalance.metered(1.23456, 5.0);
        assertThat(b.solarPowerKw()).isEqualTo(1.235);
        assertThat(b.netSurplusKw()).isEqualTo(-3.765);
        assertThat(b.solarCoveragePct()).isCloseTo(24.691, within(0.01));
    }

    @Test
    void coverageNeverExceeds100() {
        var b = PowerBalance.metered(10.0, 0.5);
        assertThat(b.solarCoveragePct()).isEqualTo(100.0);
    }

    @Test
    void unmeteredLeavesHouseAndMarginUnavailable() {
        var b = PowerBalance.unmetered(3.0);
        assertThat(b.solarPowerKw()).isEqualTo(3.0); // solar is always measured
        assertThat(b.consumptionMetered()).isFalse();
        assertThat(b.houseConsumptionKw()).isNull();
        assertThat(b.netSurplusKw()).isNull();
        assertThat(b.coveringLoad()).isNull();
        assertThat(b.solarCoveragePct()).isNull();
    }
}
