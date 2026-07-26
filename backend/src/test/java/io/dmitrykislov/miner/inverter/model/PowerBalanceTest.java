package io.dmitrykislov.miner.inverter.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * house is measured directly (Solar Analytics). surplus = solar − house;
 * grid = house − solar (+ importing, − exporting).
 */
class PowerBalanceTest {

    @Test
    void exportingWhenSolarExceedsHouse() {
        var b = PowerBalance.metered(3.0, 1.0); // solar 3, house 1
        assertThat(b.solarPowerKw()).isEqualTo(3.0);
        assertThat(b.houseConsumptionKw()).isEqualTo(1.0);
        assertThat(b.netSurplusKw()).isEqualTo(2.0);
        assertThat(b.gridPowerKw()).isEqualTo(-2.0); // exporting 2 kW
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.solarCoveragePct()).isEqualTo(100.0); // capped
        assertThat(b.consumptionMetered()).isTrue();
    }

    @Test
    void importingWhenHouseExceedsSolar() {
        var b = PowerBalance.metered(0.5, 2.0); // solar 0.5, house 2
        assertThat(b.netSurplusKw()).isEqualTo(-1.5);
        assertThat(b.gridPowerKw()).isEqualTo(1.5); // importing 1.5 kW
        assertThat(b.coveringLoad()).isFalse();
        assertThat(b.solarCoveragePct()).isEqualTo(25.0);
    }

    @Test
    void exactlyBalancedCountsAsCovering() {
        var b = PowerBalance.metered(2.0, 2.0);
        assertThat(b.netSurplusKw()).isEqualTo(0.0);
        assertThat(b.gridPowerKw()).isEqualTo(0.0);
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.solarCoveragePct()).isEqualTo(100.0);
    }

    @Test
    void nightImportWithNoSolar() {
        var b = PowerBalance.metered(0.0, 0.8); // solar 0, house 0.8
        assertThat(b.netSurplusKw()).isEqualTo(-0.8);
        assertThat(b.gridPowerKw()).isEqualTo(0.8);
        assertThat(b.coveringLoad()).isFalse();
        assertThat(b.solarCoveragePct()).isEqualTo(0.0);
    }

    @Test
    void realWorldLiveSample() {
        // Live from Solar Analytics: solar 0.23 kW, house 0.96 kW → importing ~0.73 kW.
        var b = PowerBalance.metered(0.23, 0.96);
        assertThat(b.netSurplusKw()).isCloseTo(-0.73, within(1e-6));
        assertThat(b.gridPowerKw()).isCloseTo(0.73, within(1e-6));
        assertThat(b.coveringLoad()).isFalse();
    }

    @Test
    void valuesAreRoundedToMilliUnits() {
        var b = PowerBalance.metered(1.23456, 0.5);
        assertThat(b.solarPowerKw()).isEqualTo(1.235);
        assertThat(b.houseConsumptionKw()).isEqualTo(0.5);
        assertThat(b.netSurplusKw()).isEqualTo(0.735); // 1.23456 − 0.5
    }

    @Test
    void unmeteredLeavesGridHouseAndMarginUnavailable() {
        var b = PowerBalance.unmetered(3.0);
        assertThat(b.solarPowerKw()).isEqualTo(3.0); // solar always measured
        assertThat(b.consumptionMetered()).isFalse();
        assertThat(b.gridPowerKw()).isNull();
        assertThat(b.houseConsumptionKw()).isNull();
        assertThat(b.netSurplusKw()).isNull();
        assertThat(b.coveringLoad()).isNull();
        assertThat(b.solarCoveragePct()).isNull();
    }
}
