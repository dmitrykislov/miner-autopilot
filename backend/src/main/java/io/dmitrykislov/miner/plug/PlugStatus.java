package io.dmitrykislov.miner.plug;

import java.time.Instant;

/**
 * Current state of the Tapo P110 smart plug, streamed to the UI.
 *
 * @param online       whether the last poll/command reached the plug
 * @param on           relay state — true = powered on
 * @param name         friendly name (device nickname, or configured override)
 * @param model        device model, e.g. "P110"
 * @param currentPowerW instantaneous power through the plug in watts (P110 has a
 *                      built-in energy meter); null if unavailable
 * @param todayEnergyWh energy through the plug so far today, in watt-hours; null if unavailable
 * @param timestamp    when this status was captured (server clock)
 * @param error        populated instead of data when the plug is unreachable
 */
public record PlugStatus(
        boolean online,
        boolean on,
        String name,
        String model,
        Double currentPowerW,
        Double todayEnergyWh,
        Instant timestamp,
        String error) {

    public static PlugStatus offline(String name, Instant ts, String error) {
        return new PlugStatus(false, false, name, null, null, null, ts, error);
    }
}
