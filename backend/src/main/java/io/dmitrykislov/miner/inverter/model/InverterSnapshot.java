package io.dmitrykislov.miner.inverter.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One complete poll of the inverter, broadcast to the UI over SSE.
 *
 * @param online        whether the last poll succeeded
 * @param deviceModel   e.g. "SG10RS"
 * @param serialNumber  inverter serial
 * @param runningState  resolved running state ("Standby", "Running", ...)
 * @param timestamp     when this snapshot was taken (server clock)
 * @param highlights    key numeric values pre-extracted for headline tiles
 * @param powerBalance  solar-vs-house-consumption margin (see {@link PowerBalance})
 * @param metrics       all real-time readings
 * @param strings       DC / MPPT inputs
 * @param error         populated instead of data when the poll failed
 */
public record InverterSnapshot(
        boolean online,
        String deviceModel,
        String serialNumber,
        String runningState,
        Instant timestamp,
        Map<String, Object> highlights,
        PowerBalance powerBalance,
        List<Metric> metrics,
        List<MpptString> strings,
        String error) {

    public static InverterSnapshot offline(String deviceModel, String serialNumber, Instant ts, String error) {
        // Offline: solar is unknown, so house (= solar + grid) and the margin can't be
        // computed → unavailable.
        return new InverterSnapshot(false, deviceModel, serialNumber, "Offline", ts,
                Map.of(), PowerBalance.unmetered(0.0), List.of(), List.of(), error);
    }
}
