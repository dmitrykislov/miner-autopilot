package io.dmitrykislov.miner.simulator;

import io.dmitrykislov.miner.inverter.WiNetWebSocketClient;
import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.RealPoint;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A simulated SG10RS inverter. The WiNet-S protocol is a WebSocket, which WireMock
 * can't serve, so this wraps the (Mockito) {@link WiNetWebSocketClient} bean and
 * stubs it — giving the same clean "arrange" surface as the WireMock simulators:
 * <pre>
 *   inverter.solar(2.5);   // generating 2.5 kW
 *   inverter.offline();    // poll fails → margin unavailable
 * </pre>
 */
public class MockInverter {

    private static final DeviceInfo DEV = new DeviceInfo(1, 21, "SG10RS", "SN");

    private final WiNetWebSocketClient winet;

    public MockInverter(WiNetWebSocketClient winet) {
        this.winet = winet;
        try {
            when(winet.isConnected()).thenReturn(false);      // force a (no-op) reconnect each poll
            when(winet.fetchFirstDevice()).thenReturn(DEV);
            when(winet.fetchDirect(any())).thenReturn(new DirectResponse("direct", List.of(), 0));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Current solar generation (kW), returned as the inverter's AC active power. */
    public void solar(double kw) {
        try {
            when(winet.fetchReal(any())).thenReturn(new RealResponse("real", List.of(
                    new RealPoint("I18N_COMMON_TOTAL_ACTIVE_POWER", String.valueOf(kw), "kW"))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Inverter offline: the poll fails, so solar (and the margin) is unavailable. */
    public void offline() {
        try {
            when(winet.fetchReal(any())).thenThrow(new RuntimeException("inverter offline"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
