package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.powersensor.HouseConsumptionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Polls the SG10RS every {@code house.inverter.poll-interval-ms}, converts the
 * dongle's raw datasets into an {@link InverterSnapshot} (via
 * {@link SnapshotMapper}), and publishes it. Owns connection lifecycle:
 * reconnects/re-logs-in on any failure.
 */
@Component
public class InverterPoller {

    private static final Logger log = LoggerFactory.getLogger(InverterPoller.class);

    private final WiNetWebSocketClient client;
    private final InverterStreamService stream;
    private final HouseConsumptionState houseConsumption;

    private volatile DeviceInfo device;

    public InverterPoller(WiNetWebSocketClient client, InverterStreamService stream,
                          HouseConsumptionState houseConsumption) {
        this.client = client;
        this.stream = stream;
        this.houseConsumption = houseConsumption;
    }

    /** Signed net-grid power from the Powersensor if live, else null (unavailable). */
    private Double gridNetKw() {
        return houseConsumption.measuredKw().orElse(null);
    }

    @Scheduled(fixedDelayString = "${house.inverter.poll-interval-ms:10000}",
               initialDelayString = "${house.inverter.poll-interval-ms:10000}")
    public void poll() {
        Instant now = Instant.now();
        try {
            ensureSession();
            RealResponse real = client.fetchReal(device);
            DirectResponse direct = safeDirect(device);
            InverterSnapshot snapshot = SnapshotMapper.map(device, real, direct, gridNetKw(), now);
            stream.publish(snapshot);
            log.debug("Published snapshot: state={} activePower={}",
                    snapshot.runningState(), snapshot.highlights().get("activePowerKw"));
        } catch (WiNetWebSocketClient.SessionExpiredException e) {
            log.info("Session expired, will re-login next tick");
            device = null; // force full reconnect
            publishOffline(now, "session expired");
        } catch (Exception e) {
            log.warn("Poll failed: {}", e.toString());
            device = null;
            publishOffline(now, e.getMessage());
        }
    }

    private void ensureSession() throws Exception {
        if (!client.isConnected() || device == null) {
            client.connectAndLogin();
            device = client.fetchFirstDevice();
            log.info("Bound to device {} ({}) SN={}", device.model(), device.devType(), device.serialNumber());
        }
    }

    /** {@code direct} can be unavailable on some states; treat as empty, not fatal. */
    private DirectResponse safeDirect(DeviceInfo dev) {
        try {
            return client.fetchDirect(dev);
        } catch (Exception e) {
            log.debug("direct dataset unavailable: {}", e.toString());
            return null;
        }
    }

    private void publishOffline(Instant now, String error) {
        String model = device != null ? device.model() : "SG10RS";
        String sn = device != null ? device.serialNumber() : null;
        stream.publish(InverterSnapshot.offline(model, sn, now, error));
    }
}
