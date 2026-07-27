package io.dmitrykislov.miner.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HousePropertiesTest {

    /** Build an Autopilot with the given guard-relevant fields; everything else takes its default. */
    private static HouseProperties.Autopilot ap(boolean enabled, int floorW, int headroomW, int startSurplusW) {
        return new HouseProperties.Autopilot(enabled, 0, floorW, 0, headroomW, startSurplusW,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void nestedGroupsDefaultWhenAbsent() {
        var p = new HouseProperties(null, null, null, null);
        // No hardcoded IPs/accounts — hosts default to empty and come from env.
        assertThat(p.inverter().host()).isEmpty();
        assertThat(p.inverter().port()).isEqualTo(443);        // generic protocol default kept
        assertThat(p.solarAnalytics().host()).isEqualTo("https://portal.solaranalytics.com.au/api/v3");
        assertThat(p.solarAnalytics().hasCredentials()).isFalse();
        assertThat(p.miner().host()).isEmpty();
        assertThat(p.miner().pollIntervalMs()).isEqualTo(10000);
        assertThat(p.miner().hasAuth()).isFalse();
    }

    @Test
    void minerBuildsBaseUrlFromHost() {
        var m = new HouseProperties.Miner(true, "192.168.1.80", 0, 0, null, 0, 0);
        assertThat(m.baseUrl()).isEqualTo("http://192.168.1.80");
        assertThat(m.requestTimeoutMs()).isEqualTo(8000);
        assertThat(m.hasAuth()).isFalse();
        assertThat(m.minPowerW()).isEqualTo(800);   // default hard floor
        assertThat(m.maxPowerW()).isEqualTo(3600);  // default hard ceiling
        assertThat(m.clampPower(500)).isEqualTo(800);
        assertThat(m.clampPower(5000)).isEqualTo(3600);
        assertThat(m.clampPower(1800)).isEqualTo(1800);
        assertThat(new HouseProperties.Miner(true, "h", 0, 0, "tok", 0, 0).hasAuth()).isTrue();
    }

    // When the autopilot is enabled, min-solar-w must be < min(start-surplus-w, floor-w + headroom-w),
    // else the consumption gate would strand the miner OFF. Helper: floor 1200, headroom 200 →
    // floor+headroom = 1400, so the ceiling is min(start-surplus, 1400).
    private static HouseProperties withGate(boolean enabled, int minSolarW, int floorW, int headroomW, int startSurplusW) {
        return new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, minSolarW),
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),               // min 800 / max 3600
                ap(enabled, floorW, headroomW, startSurplusW));
    }

    @Test
    void rejectsSolarGateThatWouldStrandTheMiner() {
        // floor+headroom (1400) is the binding arm here (start-surplus 2000 is higher).
        assertThatThrownBy(() -> withGate(true, 1500, 1200, 200, 2000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min-solar-w");
        assertThatThrownBy(() -> withGate(true, 1400, 1200, 200, 2000))
                .isInstanceOf(IllegalArgumentException.class); // exactly at the ceiling → still rejected
        // start-surplus (1300) is the smaller arm → ceiling = min(1300, 1400) = 1300.
        assertThatThrownBy(() -> withGate(true, 1350, 1200, 200, 1300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withGate(true, 1300, 1200, 200, 1300))
                .isInstanceOf(IllegalArgumentException.class); // equality at the start arm
    }

    @Test
    void rejectsFloorBelowMinerMinimum() {
        // autopilot floor can't be below the miner's hardware minimum (800).
        assertThatThrownBy(() -> withGate(true, 700, 700, 200, 1600))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("floor-w");
    }

    @Test
    void rejectsCeilingNotAboveFloor() {
        // miner max-power-w must exceed the autopilot floor (there'd be no ladder otherwise).
        assertThatThrownBy(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, 800),
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 1000),   // max 1000 ≤ floor 1200
                ap(true, 1200, 200, 1600)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-power-w");
    }

    @Test
    void rejectsAutopilotEnabledWithoutSolarAnalytics() {
        // The autopilot's only consumption source is Solar Analytics; with it disabled the surplus
        // is always unknown → the miner would be permanently stranded OFF. Reject at startup.
        assertThatThrownBy(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(false, null, null, null, null, 0, 0, 0, 0),
                null,
                ap(true, 1200, 200, 1600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("solar-analytics");
        // Autopilot off + Solar Analytics off is fine (nothing auto-drives the miner).
        assertThatCode(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(false, null, null, null, null, 0, 0, 0, 0),
                null,
                ap(false, 1200, 200, 1600)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSolarGateBelowCeiling() {
        // Shipped defaults: gate 800 < ceiling 1400 → valid.
        assertThatCode(() -> new HouseProperties(null, null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> withGate(true, 1399, 1200, 200, 1600)).doesNotThrowAnyException();
    }

    @Test
    void solarGateGuardDoesNotFireWhenAutopilotDisabled() {
        // Autopilot off → nothing auto-(re)starts the miner, so a high gate can't strand it;
        // the guard must not crash startup for an unused threshold.
        assertThatCode(() -> withGate(false, 5000, 1200, 200, 1600)).doesNotThrowAnyException();
    }

    @Test
    void inverterAppliesDefaultsAndBuildsWsUri() {
        var inv = new HouseProperties.Inverter(null, 0, "  ", null, null, 0, 0);
        assertThat(inv.host()).isEmpty();                      // no hardcoded IP
        assertThat(inv.wsPath()).isEqualTo("/ws/home/overview");
        assertThat(inv.pollIntervalMs()).isEqualTo(10000);
        assertThat(inv.requestTimeoutMs()).isEqualTo(8000);

        var custom = new HouseProperties.Inverter("1.2.3.4", 8443, "/ws", "u", "pw", 3000, 4000);
        assertThat(custom.wsUri()).isEqualTo("wss://1.2.3.4:8443/ws");
    }

    @Test
    void autopilotDefaultsAreStable() {
        var a = new HouseProperties.Autopilot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(a.intervalMs()).isEqualTo(30_000);
        assertThat(a.floorW()).isEqualTo(1200);
        assertThat(a.stepW()).isEqualTo(400);
        assertThat(a.headroomW()).isEqualTo(200);
        assertThat(a.startSurplusW()).isEqualTo(1600);
        assertThat(a.upMaxRungsPerCycle()).isEqualTo(2);
        assertThat(a.emergencyGapW()).isEqualTo(800);
        assertThat(a.upIntervalMs()).isEqualTo(900_000);
        assertThat(a.downIntervalMs()).isEqualTo(300_000);
        assertThat(a.shortWindowMs()).isEqualTo(180_000);
        assertThat(a.longWindowMs()).isEqualTo(900_000);
        assertThat(a.freshWithinMs()).isEqualTo(90_000);
        assertThat(a.shortCoverageMs()).isEqualTo(60_000);
        assertThat(a.longCoverageMs()).isEqualTo(300_000);
        // Start/stop hysteresis: start-surplus must sit above the floor so they can't flap.
        assertThat(a.startSurplusW()).isGreaterThan(a.floorW());
        // Up dampening must be ≥ the long window (no post-change contamination of the up-average).
        assertThat(a.upIntervalMs()).isGreaterThanOrEqualTo(a.longWindowMs());
    }

    @Test
    void solarAnalyticsAppliesDefaults() {
        var sa = new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, 0);
        assertThat(sa.host()).isEqualTo("https://portal.solaranalytics.com.au/api/v3");
        assertThat(sa.pollIntervalMs()).isEqualTo(15000);
        assertThat(sa.staleAfterSeconds()).isEqualTo(60);
        assertThat(sa.requestTimeoutMs()).isEqualTo(8000);
        assertThat(sa.minSolarWatts()).isEqualTo(800);   // default solar gate
        assertThat(sa.siteId()).isEmpty();
        assertThat(sa.hasCredentials()).isFalse();

        var withCreds = new HouseProperties.SolarAnalytics(true, "https://x", "me@x.com", "pw", "12345", 0, 0, 0, 1000);
        assertThat(withCreds.minSolarWatts()).isEqualTo(1000);
        assertThat(withCreds.hasCredentials()).isTrue();
        assertThat(withCreds.siteId()).isEqualTo("12345");
    }
}
