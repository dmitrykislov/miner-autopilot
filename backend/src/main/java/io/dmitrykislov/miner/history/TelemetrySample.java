package io.dmitrykislov.miner.history;

import java.time.Instant;

/**
 * One periodic reading of the whole system, for the history chart. Any field may be null when its
 * source is unavailable at sample time (inverter offline, consumption unmetered, miner unreachable).
 *
 * @param at            when the sample was taken (server clock)
 * @param solarW        inverter AC generation, watts
 * @param consumptionW  measured whole-home consumption, watts
 * @param minerPowerW   the miner's configured power target, watts (null when off/unreachable)
 * @param minerDrawW    the miner's live approximate draw, watts (null unless mining)
 * @param minerState    MINING / SUSPENDED / STOPPED / OFFLINE (null when unknown)
 */
public record TelemetrySample(
        Instant at,
        Double solarW,
        Double consumptionW,
        Integer minerPowerW,
        Integer minerDrawW,
        String minerState) {
}
