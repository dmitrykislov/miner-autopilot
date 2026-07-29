package io.dmitrykislov.miner.port;

import java.time.Instant;

/**
 * A source-agnostic snapshot of the live power picture, built from the {@link SolarSource} and
 * {@link ConsumptionSource} ports (the same two the autopilot reads). This is what the UI's live
 * "power flow" is driven from, so the dashboard works with <b>any</b> adapter — the built-in Sungrow
 * inverter + Solar Analytics meter, the HTTP ingest endpoint, or a custom one — with no UI change.
 *
 * <p>A {@code null} value means that source is not currently reporting a live reading (offline,
 * stale, or gated off): {@link #metered()} / {@link #hasSolar()} capture that. The rich,
 * device-specific detail (per-string DC, temperatures, serials …) stays on the adapter's own
 * endpoint (e.g. {@code /api/inverter}); this carries only what every source can provide — watts.
 *
 * @param solarW        live solar generation (W), or null if no solar source is reporting
 * @param solarAt       timestamp of the solar reading (null when {@code solarW} is null)
 * @param consumptionW  live whole-home consumption (W), or null if unmetered right now
 * @param consumptionAt timestamp of the consumption reading (null when {@code consumptionW} is null)
 */
public record PowerSnapshot(Double solarW, Instant solarAt, Double consumptionW, Instant consumptionAt) {

    /** Build from the latest reading on each port (either may be null when that source is quiet). */
    public static PowerSnapshot of(PowerReading solar, PowerReading consumption) {
        return new PowerSnapshot(
                solar != null ? solar.watts() : null,
                solar != null ? solar.at() : null,
                consumption != null ? consumption.watts() : null,
                consumption != null ? consumption.at() : null);
    }

    /** True when a live solar reading is present. */
    public boolean hasSolar() {
        return solarW != null;
    }

    /** True when a live whole-home consumption reading is present (the margin is then knowable). */
    public boolean metered() {
        return consumptionW != null;
    }
}
