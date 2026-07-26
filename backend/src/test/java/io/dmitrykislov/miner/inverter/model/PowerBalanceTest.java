package io.dmitrykislov.miner.inverter.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Powersensor clamp reports signed net-grid power: + importing, − exporting.
 * house = solar + grid ; surplus (netSurplus) = solar − house = −grid.
 */
class PowerBalanceTest {

    @Test
    void exportingWhenSolarExceedsHouse() {
        // solar 3.0, grid −1.0 (exporting 1 kW) ⇒ house 2.0, surplus 1.0
        var b = PowerBalance.metered(3.0, -1.0);
        assertThat(b.solarPowerKw()).isEqualTo(3.0);
        assertThat(b.gridPowerKw()).isEqualTo(-1.0);
        assertThat(b.houseConsumptionKw()).isEqualTo(2.0);
        assertThat(b.netSurplusKw()).isEqualTo(1.0);
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.solarCoveragePct()).isEqualTo(100.0); // capped (solar > house)
        assertThat(b.consumptionMetered()).isTrue();
    }

    @Test
    void importingWhenHouseExceedsSolar() {
        // solar 0.5, grid +1.5 (importing 1.5 kW) ⇒ house 2.0, surplus −1.5
        var b = PowerBalance.metered(0.5, 1.5);
        assertThat(b.houseConsumptionKw()).isEqualTo(2.0);
        assertThat(b.netSurplusKw()).isEqualTo(-1.5);
        assertThat(b.coveringLoad()).isFalse();
        assertThat(b.solarCoveragePct()).isEqualTo(25.0); // 0.5 / 2.0
    }

    @Test
    void exactlyBalancedCountsAsCovering() {
        var b = PowerBalance.metered(2.0, 0.0); // no grid flow ⇒ house 2.0, surplus 0
        assertThat(b.coveringLoad()).isTrue();
        assertThat(b.netSurplusKw()).isEqualTo(0.0);
        assertThat(b.houseConsumptionKw()).isEqualTo(2.0);
        assertThat(b.solarCoveragePct()).isEqualTo(100.0);
    }

    @Test
    void nightImportWithNoSolar() {
        // solar 0, grid +0.8 (importing) ⇒ house 0.8, surplus −0.8
        var b = PowerBalance.metered(0.0, 0.8);
        assertThat(b.houseConsumptionKw()).isEqualTo(0.8);
        assertThat(b.netSurplusKw()).isEqualTo(-0.8);
        assertThat(b.coveringLoad()).isFalse();
        assertThat(b.solarCoveragePct()).isEqualTo(0.0);
    }

    @Test
    void guardsTinyNegativeHouseFromSensorNoise() {
        // solar 0, grid −0.1 (exporting with no solar, e.g. battery) ⇒ house clamped to 0
        var b = PowerBalance.metered(0.0, -0.1);
        assertThat(b.houseConsumptionKw()).isEqualTo(0.0); // not negative
        assertThat(b.netSurplusKw()).isEqualTo(0.1);       // surplus = −grid, still exact
        assertThat(b.coveringLoad()).isTrue();
    }

    @Test
    void valuesAreRoundedToMilliUnits() {
        var b = PowerBalance.metered(1.23456, -0.5);
        assertThat(b.solarPowerKw()).isEqualTo(1.235);
        assertThat(b.houseConsumptionKw()).isEqualTo(0.735); // 1.23456 − 0.5
        assertThat(b.netSurplusKw()).isEqualTo(0.5);
    }

    @Test
    void realWorldExportScenario() {
        // The live case that surfaced the bug: solar 6.92 kW, grid clamp −5.231 kW.
        var b = PowerBalance.metered(6.92, -5.231);
        assertThat(b.houseConsumptionKw()).isCloseTo(1.689, within(1e-6)); // solar + grid
        assertThat(b.netSurplusKw()).isEqualTo(5.231);                     // exported surplus
        assertThat(b.coveringLoad()).isTrue();
    }

    @Test
    void unmeteredLeavesGridHouseAndMarginUnavailable() {
        var b = PowerBalance.unmetered(3.0);
        assertThat(b.solarPowerKw()).isEqualTo(3.0); // solar is always measured
        assertThat(b.consumptionMetered()).isFalse();
        assertThat(b.gridPowerKw()).isNull();
        assertThat(b.houseConsumptionKw()).isNull();
        assertThat(b.netSurplusKw()).isNull();
        assertThat(b.coveringLoad()).isNull();
        assertThat(b.solarCoveragePct()).isNull();
    }
}
