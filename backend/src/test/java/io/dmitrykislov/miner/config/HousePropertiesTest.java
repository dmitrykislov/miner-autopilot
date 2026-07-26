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

    @Test
    void rejectsSolarGateAtOrAboveStartMargin() {
        // min-solar-w must be < start-margin-w, else the gate strands the miner OFF.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, 1500),
                null,
                new HouseProperties.Autopilot(false, 0, 1000, 100, 800)))   // start 1000 ≤ gate 1500
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-solar-w");
        // Equal is also rejected (gate must be strictly below).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, null, null, null, null, 0, 0, 0, 1000),
                null,
                new HouseProperties.Autopilot(false, 0, 1000, 100, 800)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSolarGateBelowStartMargin() {
        // Shipped defaults: gate 800 < start 1000 → valid.
        org.assertj.core.api.Assertions.assertThatCode(() -> new HouseProperties(null, null, null, null))
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
