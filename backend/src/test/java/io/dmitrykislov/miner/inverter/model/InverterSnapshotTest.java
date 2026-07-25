package io.dmitrykislov.miner.inverter.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InverterSnapshotTest {

    @Test
    void offlineFactoryPopulatesSafeDefaults() {
        Instant ts = Instant.parse("2026-07-25T08:00:00Z");
        var snap = InverterSnapshot.offline("SG10RS", "A24A0965660", ts, 0.5, "boom");

        assertThat(snap.online()).isFalse();
        assertThat(snap.deviceModel()).isEqualTo("SG10RS");
        assertThat(snap.serialNumber()).isEqualTo("A24A0965660");
        assertThat(snap.runningState()).isEqualTo("Offline");
        assertThat(snap.timestamp()).isEqualTo(ts);
        assertThat(snap.error()).isEqualTo("boom");
        assertThat(snap.metrics()).isEmpty();
        assertThat(snap.strings()).isEmpty();
        assertThat(snap.highlights()).isEmpty();

        // Offline means zero solar, but if the Powersensor is still metering the
        // house the margin stays metered (not a fabricated baseline).
        assertThat(snap.powerBalance().solarPowerKw()).isEqualTo(0.0);
        assertThat(snap.powerBalance().houseConsumptionKw()).isEqualTo(0.5);
        assertThat(snap.powerBalance().netSurplusKw()).isEqualTo(-0.5);
        assertThat(snap.powerBalance().coveringLoad()).isFalse();
        assertThat(snap.powerBalance().consumptionMetered()).isTrue();
    }

    @Test
    void offlineWithNoMeterLeavesMarginUnavailable() {
        Instant ts = Instant.parse("2026-07-25T08:00:00Z");
        var snap = InverterSnapshot.offline("SG10RS", "SN", ts, null, "boom");

        assertThat(snap.powerBalance().solarPowerKw()).isEqualTo(0.0);
        assertThat(snap.powerBalance().consumptionMetered()).isFalse();
        assertThat(snap.powerBalance().houseConsumptionKw()).isNull();
        assertThat(snap.powerBalance().netSurplusKw()).isNull();
    }
}
