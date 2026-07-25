package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.MpptEntry;
import io.dmitrykislov.miner.inverter.dto.RealPoint;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.powersensor.HouseConsumptionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InverterPollerTest {

    private WiNetWebSocketClient client;
    private InverterStreamService stream;
    private HouseLoadState houseLoad;
    private HouseConsumptionState houseConsumption;
    private InverterPoller poller;

    private static final DeviceInfo DEV = new DeviceInfo(1, 21, "SG10RS", "A24A0965660");

    @BeforeEach
    void setup() {
        client = mock(WiNetWebSocketClient.class);
        stream = mock(InverterStreamService.class);
        houseLoad = mock(HouseLoadState.class);
        houseConsumption = mock(HouseConsumptionState.class);
        when(houseLoad.get()).thenReturn(1.0);
        when(houseConsumption.measuredKw()).thenReturn(Optional.empty()); // no live meter -> baseline
        poller = new InverterPoller(client, stream, houseLoad, houseConsumption);
    }

    private InverterSnapshot capturePublished() {
        ArgumentCaptor<InverterSnapshot> cap = ArgumentCaptor.forClass(InverterSnapshot.class);
        verify(stream).publish(cap.capture());
        return cap.getValue();
    }

    @Test
    void happyPathConnectsMapsAndPublishesOnlineSnapshot() throws Exception {
        when(client.isConnected()).thenReturn(false);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenReturn(new RealResponse("real", List.of(
                new RealPoint("I18N_COMMON_TOTAL_ACTIVE_POWER", "3.0", "kW"))));
        when(client.fetchDirect(DEV)).thenReturn(new DirectResponse("direct", List.of(
                new MpptEntry("MPPT1", "580.0", "V", "5.0", "A")), 1));

        poller.poll();

        verify(client).connectAndLogin();
        InverterSnapshot s = capturePublished();
        assertThat(s.online()).isTrue();
        assertThat(s.deviceModel()).isEqualTo("SG10RS");
        assertThat(s.strings()).hasSize(1);
        assertThat(s.powerBalance().solarPowerKw()).isEqualTo(3.0);
        assertThat(s.powerBalance().netSurplusKw()).isEqualTo(2.0); // 3.0 - 1.0 house
    }

    @Test
    void usesMeasuredConsumptionWhenAvailable() throws Exception {
        when(client.isConnected()).thenReturn(false);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenReturn(new RealResponse("real", List.of(
                new RealPoint("I18N_COMMON_TOTAL_ACTIVE_POWER", "4.0", "kW"))));
        when(client.fetchDirect(DEV)).thenReturn(new DirectResponse("direct", List.of(), 0));
        when(houseConsumption.measuredKw()).thenReturn(Optional.of(1.5)); // live meter reading

        poller.poll();

        InverterSnapshot s = capturePublished();
        assertThat(s.powerBalance().houseConsumptionKw()).isEqualTo(1.5);
        assertThat(s.powerBalance().consumptionMetered()).isTrue();
        assertThat(s.powerBalance().netSurplusKw()).isEqualTo(2.5); // 4.0 - 1.5
    }

    @Test
    void reusesExistingSessionWhenConnected() throws Exception {
        // First poll establishes the session/device.
        when(client.isConnected()).thenReturn(false).thenReturn(true);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenReturn(new RealResponse("real", List.of()));
        when(client.fetchDirect(DEV)).thenReturn(new DirectResponse("direct", List.of(), 0));

        poller.poll();
        poller.poll();

        // connect/login and device lookup happen only once despite two polls.
        verify(client, times(1)).connectAndLogin();
        verify(client, times(1)).fetchFirstDevice();
        verify(stream, times(2)).publish(any());
    }

    @Test
    void directFailureIsSwallowedAndSnapshotStaysOnline() throws Exception {
        when(client.isConnected()).thenReturn(false);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenReturn(new RealResponse("real", List.of()));
        when(client.fetchDirect(DEV)).thenThrow(new RuntimeException("direct unavailable"));

        poller.poll();

        InverterSnapshot s = capturePublished();
        assertThat(s.online()).isTrue();
        assertThat(s.strings()).isEmpty();
    }

    @Test
    void sessionExpiredPublishesOfflineAndForcesReconnect() throws Exception {
        when(client.isConnected()).thenReturn(false);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenThrow(new WiNetWebSocketClient.SessionExpiredException("106"));

        poller.poll();

        InverterSnapshot s = capturePublished();
        assertThat(s.online()).isFalse();
        assertThat(s.error()).contains("session expired");

        // Next poll must reconnect (device was reset). Use doReturn to re-stub
        // without invoking the previously-throwing fetchReal stub.
        doReturn(new RealResponse("real", List.of())).when(client).fetchReal(DEV);
        doReturn(new DirectResponse("direct", List.of(), 0)).when(client).fetchDirect(DEV);
        poller.poll();
        verify(client, times(2)).connectAndLogin();
    }

    @Test
    void genericFailurePublishesOfflineSnapshot() throws Exception {
        when(client.isConnected()).thenReturn(false);
        when(client.fetchFirstDevice()).thenReturn(DEV);
        when(client.fetchReal(DEV)).thenThrow(new RuntimeException("boom"));

        poller.poll();

        InverterSnapshot s = capturePublished();
        assertThat(s.online()).isFalse();
        assertThat(s.deviceModel()).isEqualTo("SG10RS");
        assertThat(s.error()).contains("boom");
    }
}
