package io.dmitrykislov.miner.autopilot;

/**
 * The outcome of one autopilot evaluation — a pure value describing what should
 * happen to the miner, with the human-readable reason.
 *
 * @param action        what to do
 * @param targetPowerW  the power target to apply (for START and STEP_UP/DOWN);
 *                      0 / ignored for STOP and NONE
 * @param reason        why this decision was made (for logs / assertions)
 */
public record AutopilotDecision(Action action, int targetPowerW, String reason) {

    public enum Action {
        /** Miner is off and there is enough surplus — start it at the minimum power. */
        START,
        /** Miner is on — raise the power target by one step (bounded by max). */
        STEP_UP,
        /** Miner is on — lower the power target by one step (still ≥ min). */
        STEP_DOWN,
        /** Miner is on at the floor and margin is too low — turn it off. */
        STOP,
        /** Do nothing (off with too little surplus, at max/min, or within the deadzone). */
        NONE
    }

    static AutopilotDecision of(Action action, int targetPowerW, String reason) {
        return new AutopilotDecision(action, targetPowerW, reason);
    }
}
