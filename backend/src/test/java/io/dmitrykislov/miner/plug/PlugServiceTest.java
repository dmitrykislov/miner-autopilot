package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class PlugServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private HouseProperties props(String email, String pw, String name) {
        return new HouseProperties(null, null,
                new HouseProperties.Plug(true, "192.168.4.36", email, pw, name, 10000, 8000,
                        "local", "", "https://wap.tplinkcloud.com"), null, null);
    }

    private PlugService service(PlugTransport client, PlugStreamService stream, HouseProperties props) {
        return new PlugService(client, stream, props);
    }

    @Test
    void refreshParsesInfoNicknameAndEnergy() throws Exception {
        var client = mock(PlugTransport.class);
        var stream = new PlugStreamService();
        when(client.getDeviceInfo()).thenReturn(mapper.readTree(
                "{\"device_on\":true,\"nickname\":\"TGl2aW5nIFJvb20=\",\"model\":\"P110\"}")); // "Living Room"
        when(client.getEnergyUsage()).thenReturn(mapper.readTree(
                "{\"current_power\":150000,\"today_energy\":320}")); // mW, Wh

        PlugStatus s = service(client, stream, props("me@x.com", "pw", "")).refresh();

        assertThat(s.online()).isTrue();
        assertThat(s.on()).isTrue();
        assertThat(s.name()).isEqualTo("Living Room");
        assertThat(s.model()).isEqualTo("P110");
        assertThat(s.currentPowerW()).isCloseTo(150.0, within(1e-9)); // 150000 mW -> 150 W
        assertThat(s.todayEnergyWh()).isEqualTo(320.0);
        assertThat(stream.latest()).isSameAs(s); // published
    }

    @Test
    void configuredNameOverridesNickname() throws Exception {
        var client = mock(PlugTransport.class);
        when(client.getDeviceInfo()).thenReturn(mapper.readTree(
                "{\"device_on\":false,\"nickname\":\"TGl2aW5nIFJvb20=\",\"model\":\"P110\"}"));
        when(client.getEnergyUsage()).thenThrow(new RuntimeException("no energy"));

        PlugStatus s = service(client, new PlugStreamService(), props("me@x.com", "pw", "Heater")).refresh();
        assertThat(s.name()).isEqualTo("Heater");
        assertThat(s.on()).isFalse();
        assertThat(s.currentPowerW()).isNull(); // energy unavailable, tolerated
    }

    @Test
    void refreshWithoutCredentialsIsOfflineWithReason() {
        var client = mock(PlugTransport.class);
        PlugStatus s = service(client, new PlugStreamService(), props("", "", "")).refresh();
        assertThat(s.online()).isFalse();
        assertThat(s.error()).contains("credentials");
        verifyNoInteractions(client);
    }

    @Test
    void refreshOfflineOnClientError() throws Exception {
        var client = mock(PlugTransport.class);
        when(client.getDeviceInfo()).thenThrow(new RuntimeException("boom"));
        PlugStatus s = service(client, new PlugStreamService(), props("me@x.com", "pw", "")).refresh();
        assertThat(s.online()).isFalse();
        assertThat(s.error()).contains("boom");
    }

    @Test
    void authFailureReportedClearly() throws Exception {
        var client = mock(PlugTransport.class);
        when(client.getDeviceInfo()).thenThrow(new PlugTransport.AuthException("bad creds"));
        PlugStatus s = service(client, new PlugStreamService(), props("me@x.com", "pw", "")).refresh();
        assertThat(s.online()).isFalse();
        assertThat(s.error()).contains("authentication failed");
    }

    @Test
    void setOnInvokesClientThenRefreshes() throws Exception {
        var client = mock(PlugTransport.class);
        when(client.getDeviceInfo()).thenReturn(mapper.readTree("{\"device_on\":true,\"model\":\"P110\"}"));
        when(client.getEnergyUsage()).thenThrow(new RuntimeException("skip"));

        PlugStatus s = service(client, new PlugStreamService(), props("me@x.com", "pw", "")).setOn(true);
        verify(client).setOn(true);
        assertThat(s.on()).isTrue();
    }

    @Test
    void toggleFlipsLastKnownState() throws Exception {
        var client = mock(PlugTransport.class);
        var stream = new PlugStreamService();
        // last known = ON
        stream.publish(new PlugStatus(true, true, "P", "P110", null, null, java.time.Instant.now(), null));
        when(client.getDeviceInfo()).thenReturn(mapper.readTree("{\"device_on\":false,\"model\":\"P110\"}"));
        when(client.getEnergyUsage()).thenThrow(new RuntimeException("skip"));

        service(client, stream, props("me@x.com", "pw", "")).toggle();
        verify(client).setOn(false); // toggled from on -> off
    }
}
