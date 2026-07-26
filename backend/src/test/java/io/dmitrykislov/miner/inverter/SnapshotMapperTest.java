package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.MpptEntry;
import io.dmitrykislov.miner.inverter.dto.RealPoint;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SnapshotMapperTest {

    private static final DeviceInfo DEV = new DeviceInfo(1, 21, "SG10RS", "A24A0965660");
    private static final Instant TS = Instant.parse("2026-07-25T08:00:00Z");

    private RealResponse real(RealPoint... pts) {
        return new RealResponse("real", List.of(pts));
    }

    private RealPoint pt(String name, String value, String unit) {
        return new RealPoint(name, value, unit);
    }

    @Test
    void mapsMetricsHighlightsStateAndSolarPower() {
        RealResponse real = real(
                pt("I18N_COMMON_DAILY_POWER_YIELD", "40.9", "kWh"),
                pt("I18N_COMMON_TOTAL_ACTIVE_POWER", "3.20", "kW"),
                pt("I18N_COMMON_RUNNING_STATE", "I18N_COMMON_RUNNING", ""),
                pt("I18N_COMMON_GRID_FREQUENCY", "50.01", "Hz"),
                pt("I18N_CONFIG_KEY_1003334", "--", "V")  // unavailable -> not a highlight
        );

        // grid −1.0 kW = exporting 1 kW
        InverterSnapshot s = SnapshotMapper.map(DEV, real, null, -1.0, TS);

        assertThat(s.online()).isTrue();
        assertThat(s.deviceModel()).isEqualTo("SG10RS");
        assertThat(s.serialNumber()).isEqualTo("A24A0965660");
        assertThat(s.timestamp()).isEqualTo(TS);
        assertThat(s.metrics()).hasSize(5);

        // running state is de-i18n'd
        assertThat(s.runningState()).isEqualTo("Running");

        // numeric highlights extracted; "--" excluded
        assertThat(s.highlights())
                .containsEntry("dailyYieldKwh", 40.9)
                .containsEntry("activePowerKw", 3.2)
                .containsEntry("gridFrequencyHz", 50.01);

        // solar 3.2 measured; grid −1.0 ⇒ house 2.2, surplus 1.0
        assertThat(s.powerBalance().solarPowerKw()).isEqualTo(3.2);
        assertThat(s.powerBalance().gridPowerKw()).isEqualTo(-1.0);
        assertThat(s.powerBalance().houseConsumptionKw()).isEqualTo(2.2);
        assertThat(s.powerBalance().netSurplusKw()).isEqualTo(1.0);
        assertThat(s.powerBalance().coveringLoad()).isTrue();
    }

    @Test
    void computesMpptPowerFromVoltageAndCurrent() {
        DirectResponse direct = new DirectResponse("direct", List.of(
                new MpptEntry("MPPT1", "580.0", "V", "5.0", "A"),
                new MpptEntry("MPPT2", "0.0", "V", "0.0", "A"),
                new MpptEntry(null, "100.0", "V", "2.0", "A")   // null name -> default
        ), 3);

        InverterSnapshot s = SnapshotMapper.map(DEV, null, direct, 0.5, TS);

        assertThat(s.strings()).hasSize(3);
        assertThat(s.strings().get(0).powerKw()).isCloseTo(2.9, within(1e-9)); // 580*5/1000
        assertThat(s.strings().get(1).powerKw()).isEqualTo(0.0);
        assertThat(s.strings().get(2).name()).isEqualTo("MPPT");
        assertThat(s.strings().get(2).powerKw()).isCloseTo(0.2, within(1e-9)); // 100*2/1000
    }

    @Test
    void handlesNullDatasetsGracefully() {
        InverterSnapshot s = SnapshotMapper.map(DEV, null, null, 0.5, TS);
        assertThat(s.online()).isTrue();
        assertThat(s.metrics()).isEmpty();
        assertThat(s.strings()).isEmpty();
        assertThat(s.runningState()).isEqualTo("Unknown");
        assertThat(s.powerBalance().solarPowerKw()).isEqualTo(0.0);
        assertThat(s.powerBalance().netSurplusKw()).isEqualTo(-0.5);
    }

    @Test
    void nonNumericActivePowerYieldsZeroSolar() {
        RealResponse real = real(pt("I18N_COMMON_TOTAL_ACTIVE_POWER", "--", "kW"));
        InverterSnapshot s = SnapshotMapper.map(DEV, real, null, 2.0, TS);
        assertThat(s.powerBalance().solarPowerKw()).isEqualTo(0.0);
        assertThat(s.highlights()).doesNotContainKey("activePowerKw");
    }

    @Test
    void meteredWhenGridPresentUnmeteredWhenNull() {
        RealResponse real = real(pt("I18N_COMMON_TOTAL_ACTIVE_POWER", "2.0", "kW"));

        // grid −0.5 kW (exporting) with solar 2.0 ⇒ house 1.5, surplus 0.5
        var metered = SnapshotMapper.map(DEV, real, null, -0.5, TS).powerBalance();
        assertThat(metered.consumptionMetered()).isTrue();
        assertThat(metered.gridPowerKw()).isEqualTo(-0.5);
        assertThat(metered.houseConsumptionKw()).isEqualTo(1.5);
        assertThat(metered.netSurplusKw()).isEqualTo(0.5);

        // No live meter reading → grid + house + margin unavailable (no assumed baseline).
        var unmetered = SnapshotMapper.map(DEV, real, null, null, TS).powerBalance();
        assertThat(unmetered.consumptionMetered()).isFalse();
        assertThat(unmetered.gridPowerKw()).isNull();
        assertThat(unmetered.houseConsumptionKw()).isNull();
        assertThat(unmetered.netSurplusKw()).isNull();
        assertThat(unmetered.solarPowerKw()).isEqualTo(2.0); // solar still measured
    }

    @Test
    void parseNumberRejectsBlankDashAndGarbage() {
        assertThat(SnapshotMapper.parseNumber(null)).isNull();
        assertThat(SnapshotMapper.parseNumber("  ")).isNull();
        assertThat(SnapshotMapper.parseNumber("--")).isNull();
        assertThat(SnapshotMapper.parseNumber("abc")).isNull();
        assertThat(SnapshotMapper.parseNumber(" 12.5 ")).isEqualTo(12.5);
        assertThat(SnapshotMapper.parseOrZero("--")).isEqualTo(0.0);
    }
}
