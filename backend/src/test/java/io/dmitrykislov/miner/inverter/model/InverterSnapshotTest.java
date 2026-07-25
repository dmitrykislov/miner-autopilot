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

        // Even offline, the margin reflects zero solar against the assumed load.
        assertThat(snap.powerBalance().solarPowerKw()).isEqualTo(0.0);
        assertThat(snap.powerBalance().houseConsumptionKw()).isEqualTo(0.5);
        assertThat(snap.powerBalance().netSurplusKw()).isEqualTo(-0.5);
        assertThat(snap.powerBalance().coveringLoad()).isFalse();
    }
}
