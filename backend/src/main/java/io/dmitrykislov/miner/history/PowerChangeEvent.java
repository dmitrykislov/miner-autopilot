package io.dmitrykislov.miner.history;

import java.time.Instant;

/**
 * A discrete autopilot-driven change to the miner, shown as a marker on the history chart.
 *
 * @param at     when the change was made
 * @param action START / STEP_UP / STEP_DOWN / STOP
 * @param fromW  power target before the change (null when the miner was off)
 * @param toW    power target after the change (null when the miner was turned off)
 * @param reason the autopilot's human-readable reason
 */
public record PowerChangeEvent(
        Instant at,
        String action,
        Integer fromW,
        Integer toW,
        String reason) {
}
