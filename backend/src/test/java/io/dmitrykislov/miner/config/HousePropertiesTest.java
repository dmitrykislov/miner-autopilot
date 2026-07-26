package io.dmitrykislov.miner.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HousePropertiesTest {

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

    // min-solar-w must be < min(start-margin-w, min-power-w + low-margin-w) when the autopilot
    // is enabled, else the gate would strand the miner OFF (can't start, or a running hold stopped).
    // Helper: min 800, low 100, step 800 → ceiling = min(start, 900).
    private static HouseProperties withGate(boolean autopilotEnabled, int minSolarW, int startMarginW) {
        return new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, minSolarW),
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),               // min 800 / max 3600
                new HouseProperties.Autopilot(autopilotEnabled, 0, startMarginW, 100, 800));
    }

    @Test
    void rejectsSolarGateThatWouldStrandTheMiner() {
        // Above the start threshold → can't start.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withGate(true, 1500, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min-solar-w");
        // Between minPower+low (900) and start (1000): passes the old start-only check but would
        // stop a validly-running miner — must still be rejected by the tighter ceiling.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withGate(true, 950, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        // Exactly at the ceiling (900) is rejected too (gate must be strictly below).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withGate(true, 900, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        // When start-margin-w is the smaller arm it binds: ceiling = min(850, 900) = 850.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withGate(true, 875, 850))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withGate(true, 850, 850))
                .isInstanceOf(IllegalArgumentException.class); // equality at the start arm
    }

    @Test
    void rejectsAutopilotEnabledWithoutSolarAnalytics() {
        // The autopilot's only consumption source is Solar Analytics; with it disabled the margin
        // is always unknown → the miner would be permanently stranded OFF. Reject at startup.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(false, null, null, null, null, 0, 0, 0, 0),
                null,
                new HouseProperties.Autopilot(true, 0, 1000, 100, 800)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("solar-analytics");
        // Autopilot off + Solar Analytics off is fine (nothing auto-drives the miner).
        org.assertj.core.api.Assertions.assertThatCode(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(false, null, null, null, null, 0, 0, 0, 0),
                null,
                new HouseProperties.Autopilot(false, 0, 1000, 100, 800)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSolarGateBelowCeiling() {
        // Shipped defaults: gate 800 < ceiling 900 → valid.
        org.assertj.core.api.Assertions.assertThatCode(() -> new HouseProperties(null, null, null, null))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> withGate(true, 899, 1000))
                .doesNotThrowAnyException();
    }

    @Test
    void solarGateGuardDoesNotFireWhenAutopilotDisabled() {
        // Autopilot off → nothing auto-(re)starts the miner, so a high gate can't strand it;
        // the guard must not crash startup for an unused threshold.
        org.assertj.core.api.Assertions.assertThatCode(() -> withGate(false, 5000, 800))
                .doesNotThrowAnyException();
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
        var a = new HouseProperties.Autopilot(false, 0, 0, 0, 0);
        assertThat(a.intervalMs()).isEqualTo(30000);
        assertThat(a.startMarginW()).isEqualTo(1000);
        assertThat(a.lowMarginW()).isEqualTo(100);
        assertThat(a.stepW()).isEqualTo(800);   // was 1000 — narrowed so the deadzone ≥ step
        // The shipped thresholds must not be oscillation-prone: deadzone ≥ one step.
        assertThat(io.dmitrykislov.miner.autopilot.MinerAutopilotPlanner
                .isStableConfig(a.startMarginW(), a.lowMarginW(), a.stepW())).isTrue();
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
