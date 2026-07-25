package io.dmitrykislov.miner.braiins;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class MinerServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private HouseProperties props(boolean enabled, String host) {
        return new HouseProperties(null, null, null,
                new HouseProperties.Miner(enabled, host, 10000, 8000, "", 0, 0), null);
    }

    private MinerService svc(BraiinsMinerClient client, MinerStreamService stream, boolean enabled, String host) {
        return new MinerService(client, stream, props(enabled, host));
    }

    private JsonNode node(String json) {
        return mapper.readTree(json);
    }

    @Test
    void stoppedStatusReportsPowerTargetAndNoSummary() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        when(client.status()).thenReturn(node(
                "{\"info\":{\"modelName\":\"Antminer S19k Pro\",\"poolGroups\":[]},\"uptime\":null," +
                "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":1200}}}"));

        MinerStatus s = svc(client, new MinerStreamService(), true, "192.168.4.28").refresh();

        assertThat(s.reachable()).isTrue();
        assertThat(s.running()).isFalse();
        assertThat(s.state()).isEqualTo(MinerStatus.STOPPED);
        assertThat(s.model()).isEqualTo("Antminer S19k Pro");
        assertThat(s.powerTargetW()).isEqualTo(1200);
        assertThat(s.hashrateThs()).isNull();
        assertThat(s.fans()).isEmpty();
        verify(client, never()).realtime(); // not running → no realtime call
    }

    @Test
    void runningWithActivePoolAndHashrateIsMining() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        when(client.status()).thenReturn(node(
                "{\"info\":{\"modelName\":\"Antminer S19k Pro\",\"poolGroups\":[{\"pools\":[{\"url\":\"x\",\"active\":true}]}]}," +
                "\"uptime\":{\"durationS\":3600},\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":1200}}}"));
        when(client.realtime()).thenReturn(node(
                "{\"summary\":{\"realHashrate\":{\"mhs5S\":95000000},\"power\":{\"approxConsumptionW\":1150,\"limitW\":1200}}," +
                "\"fans\":[{\"name\":\"fan1\",\"rpm\":3000,\"speed\":80},{\"name\":\"fan2\",\"rpm\":3120,\"speed\":80}]}"));

        MinerStatus s = svc(client, new MinerStreamService(), true, "192.168.4.28").refresh();

        assertThat(s.state()).isEqualTo(MinerStatus.MINING);
        assertThat(s.running()).isTrue();
        assertThat(s.activePools()).isEqualTo(1);
        assertThat(s.totalPools()).isEqualTo(1);
        assertThat(s.uptimeSeconds()).isEqualTo(3600L);
        assertThat(s.hashrateThs()).isCloseTo(95.0, within(1e-9)); // 95_000_000 MH/s → 95 TH/s
        assertThat(s.powerDrawW()).isEqualTo(1150);
        assertThat(s.fans()).hasSize(2);
        assertThat(s.fans().get(0).rpm()).isEqualTo(3000);
    }

    @Test
    void serviceUpButNoPoolIsSuspendedWithReason() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        // service up (uptime present) but no pools configured → dead pools
        when(client.status()).thenReturn(node(
                "{\"info\":{\"modelName\":\"Antminer S19k Pro\",\"poolGroups\":[{\"name\":\"Default\",\"pools\":[]}]}," +
                "\"uptime\":{\"durationS\":58},\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":1200}}}"));
        // realtime unavailable while suspended
        when(client.realtime()).thenThrow(new IllegalStateException("Service unavailable"));

        MinerStatus s = svc(client, new MinerStreamService(), true, "192.168.4.28").refresh();

        assertThat(s.running()).isTrue();          // service is up
        assertThat(s.state()).isEqualTo(MinerStatus.SUSPENDED);
        assertThat(s.hashrateThs()).isNull();
        assertThat(s.activePools()).isZero();
        assertThat(s.statusReason()).contains("no pool configured");
    }

    @Test
    void offlineWhenClientThrows() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        when(client.status()).thenThrow(new RuntimeException("connection refused"));
        MinerStatus s = svc(client, new MinerStreamService(), true, "192.168.4.28").refresh();
        assertThat(s.reachable()).isFalse();
        assertThat(s.error()).contains("connection refused");
    }

    @Test
    void offlineWhenDisabledOrNoHost() {
        var client = mock(BraiinsMinerClient.class);
        assertThat(svc(client, new MinerStreamService(), false, "192.168.4.28").refresh().reachable()).isFalse();
        assertThat(svc(client, new MinerStreamService(), true, "").refresh().reachable()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void startStopSetPowerDelegateThenRefresh() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        when(client.status()).thenReturn(node("{\"info\":{\"modelName\":\"S\"},\"uptime\":null,\"config\":{}}"));
        var service = svc(client, new MinerStreamService(), true, "192.168.4.28");

        service.start();
        verify(client).start();
        service.stop();
        verify(client).stop();
        service.setPowerTarget(1400, true);
        verify(client).setPowerTarget(1400, true);
        verify(client, times(3)).status(); // each command refreshes
    }
}
