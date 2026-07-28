package io.dmitrykislov.miner.autopilot;

import java.time.Instant;

/**
 * A snapshot of the autopilot's state for the UI: whether it's on, what it decided on
 * its last evaluation, and the details/time of the last change it actually made to the miner.
 *
 * @param enabled      whether the control loop is currently active
 * @param evaluatedAt  when the last tick ran (null if it hasn't ticked yet)
 * @param lastDecision human-readable summary of the most recent decision (incl. "hold"/skips)
 * @param lastChangeAt when the autopilot last actually changed the miner (null if never)
 * @param lastChange   details of that change (null if the autopilot has never changed the miner)
 */
public record AutopilotStatus(
        boolean enabled,
        Instant evaluatedAt,
        String lastDecision,
        Instant lastChangeAt,
        Change lastChange) {

    /**
     * One autopilot-driven change to the miner.
     *
     * @param at         when it happened
     * @param action     START / STEP_UP / STEP_DOWN / STOP
     * @param fromPowerW power target before the change (null when the miner was off)
     * @param toPowerW   power target after the change (null when the miner was turned off)
     * @param detail     the decision reason behind the change
     */
    public record Change(Instant at, String action, Integer fromPowerW, Integer toPowerW, String detail) {}
}
