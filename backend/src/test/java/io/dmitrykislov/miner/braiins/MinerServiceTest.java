package io.dmitrykislov.miner.braiins;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class MinerServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private HouseProperties props(boolean enabled, String host) {
        return new HouseProperties(null, null,
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

    @Test
    void setPowerTargetReadsBackTheAppliedTarget() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        // The miner reflects the new target on the next status read → the read-back confirms it.
        when(client.status()).thenReturn(node(
                "{\"info\":{\"modelName\":\"S\"},\"uptime\":null," +
                "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":2800}}}"));
        var service = svc(client, new MinerStreamService(), true, "192.168.4.28");

        MinerStatus after = service.setPowerTarget(2800, true);

        verify(client).setPowerTarget(2800, true);
        verify(client).status();                       // the set is followed by one verifying read-back
        assertThat(after.powerTargetW()).isEqualTo(2800); // miner confirms the applied target
    }

    @Test
    void setPowerTargetSurfacesAReadBackMismatch() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        // Command sent for 2800 but the miner still reports 1200 → mismatch (WARN path).
        when(client.status()).thenReturn(node(
                "{\"info\":{\"modelName\":\"S\"},\"uptime\":null," +
                "\"config\":{\"autotuning\":{\"enabled\":true,\"powerTarget\":1200}}}"));
        var service = svc(client, new MinerStreamService(), true, "192.168.4.28");

        MinerStatus after = service.setPowerTarget(2800, true);

        verify(client).setPowerTarget(2800, true);
        assertThat(after.powerTargetW()).isEqualTo(1200); // read-back surfaces what the miner really has
    }

    @Test
    void setPowerTargetLogsWarnWhenReadBackDoesNotMatch() throws Exception {
        // Directly verifies the NEW read-back logging: a mismatch must WARN. (This would fail if the
        // read-back/verify block were removed, unlike the return-value characterisation tests.)
        Logger logger = (Logger) LoggerFactory.getLogger(MinerService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var client = mock(BraiinsMinerClient.class);
            when(client.status()).thenReturn(node(
                    "{\"info\":{\"modelName\":\"S\"},\"uptime\":null," +
                    "\"config\":{\"autotuning\":{\"powerTarget\":1200}}}")); // miner reports 1200, not 2800
            svc(client, new MinerStreamService(), true, "192.168.4.28").setPowerTarget(2800, true);

            assertThat(appender.list).anyMatch(e ->
                    e.getLevel() == Level.WARN && e.getFormattedMessage().contains("may not have applied"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void setPowerTargetToleratesNullReadBackTarget() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        // A stopped/unreachable miner may report no power target → the read-back must not NPE.
        when(client.status()).thenReturn(node("{\"info\":{\"modelName\":\"S\"},\"uptime\":null,\"config\":{}}"));
        var service = svc(client, new MinerStreamService(), true, "192.168.4.28");

        MinerStatus after = service.setPowerTarget(2800, true);

        assertThat(after.powerTargetW()).isNull();
    }

    @Test
    void setPowerTargetClampsToHardwareLimits() throws Exception {
        var client = mock(BraiinsMinerClient.class);
        when(client.status()).thenReturn(node("{\"info\":{\"modelName\":\"S\"},\"uptime\":null,\"config\":{}}"));
        var service = svc(client, new MinerStreamService(), true, "192.168.4.28"); // min 800 / max 3600

        service.setPowerTarget(99999, true);   // above the ceiling → clamped to max
        verify(client).setPowerTarget(3600, true);
        service.setPowerTarget(100, true);     // below the floor → clamped to min
        verify(client).setPowerTarget(800, true);
        service.setPowerTarget(1400, true);    // in range → passed through unchanged
        verify(client).setPowerTarget(1400, true);
        verify(client, never()).setPowerTarget(eq(99999), anyBoolean());
        verify(client, never()).setPowerTarget(eq(100), anyBoolean());
    }
}
