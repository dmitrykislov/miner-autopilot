package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.autopilot.AutopilotDecision.Action;

/**
 * Pure decision logic for the solar-margin miner autopilot. No I/O, no state —
 * given the current power margin and miner state it returns what should happen.
 * This is the single source of truth for the control loop and is exhaustively
 * unit-tested.
 *
 * <p><b>Margin</b> = solar generation − whole-home consumption, in watts
 * (positive = surplus that would be exported). Because the miner's own draw is
 * part of the measured house consumption, the margin already reflects the miner
 * when it is running.
 *
 * <p><b>Rules</b> (thresholds configurable):
 * <ul>
 *   <li>Miner off &amp; margin ≥ {@code startMarginW} → <b>START</b> at {@code minPowerW}
 *       (starting draws {@code minPowerW}, leaving {@code margin − minPowerW} surplus).</li>
 *   <li>Miner on &amp; margin ≥ {@code startMarginW} → <b>STEP_UP</b> by {@code stepW}
 *       (capped at {@code maxPowerW}; NONE if already at max).</li>
 *   <li>Miner on &amp; margin &lt; {@code lowMarginW} → <b>STEP_DOWN</b> by {@code stepW};
 *       if that would fall below {@code minPowerW} → <b>STOP</b>.</li>
 *   <li>Otherwise (deadzone {@code [lowMarginW, startMarginW)}) → <b>NONE</b> (hold).</li>
 * </ul>
 */
public final class MinerAutopilotPlanner {

    private final int minPowerW;
    private final int maxPowerW;
    private final int startMarginW;
    private final int lowMarginW;
    private final int stepW;

    public MinerAutopilotPlanner(int minPowerW, int maxPowerW, int startMarginW, int lowMarginW, int stepW) {
        if (minPowerW <= 0 || maxPowerW < minPowerW) {
            throw new IllegalArgumentException("invalid power limits: min=" + minPowerW + " max=" + maxPowerW);
        }
        if (lowMarginW >= startMarginW) {
            throw new IllegalArgumentException("lowMarginW must be < startMarginW");
        }
        this.minPowerW = minPowerW;
        this.maxPowerW = maxPowerW;
        this.startMarginW = startMarginW;
        this.lowMarginW = lowMarginW;
        this.stepW = stepW;
    }

    /**
     * @param marginW        current power margin (solar − house), watts. Because the
     *                       meter includes the miner, this already reflects the miner's
     *                       draw when it is actually mining.
     * @param mining         whether the miner is actively hashing (drawing power that
     *                       the margin already accounts for); a suspended miner is NOT
     *                       mining and must not be treated as such by the caller
     * @param currentPowerW  the miner's current power target (watts); ignored when not mining
     */
    public AutopilotDecision decide(double marginW, boolean mining, int currentPowerW) {
        long m = Math.round(marginW);

        if (!mining) {
            if (marginW >= startMarginW) {
                return AutopilotDecision.of(Action.START, minPowerW,
                        "margin " + m + "W ≥ start " + startMarginW + "W → start miner at min " + minPowerW + "W");
            }
            return AutopilotDecision.of(Action.NONE, 0,
                    "margin " + m + "W < start " + startMarginW + "W → stay off");
        }

        // Running.
        if (marginW >= startMarginW) {
            int next = Math.min(currentPowerW + stepW, maxPowerW);
            if (next > currentPowerW) {
                return AutopilotDecision.of(Action.STEP_UP, next,
                        "surplus " + m + "W ≥ " + startMarginW + "W → +" + stepW + "W to " + next + "W");
            }
            return AutopilotDecision.of(Action.NONE, currentPowerW,
                    "surplus " + m + "W but already at max " + maxPowerW + "W → hold");
        }

        if (marginW < lowMarginW) {
            // Only stop when already at (or below) the floor; otherwise reduce toward
            // it — never undershoot the hard min, and never stop prematurely from an
            // off-ladder target (e.g. 1200 W → 800 W, not STOP).
            if (currentPowerW <= minPowerW) {
                return AutopilotDecision.of(Action.STOP, 0,
                        "margin " + m + "W < " + lowMarginW + "W and at floor " + minPowerW + "W → stop miner");
            }
            int next = Math.max(currentPowerW - stepW, minPowerW);
            return AutopilotDecision.of(Action.STEP_DOWN, next,
                    "margin " + m + "W < " + lowMarginW + "W → down to " + next + "W");
        }

        return AutopilotDecision.of(Action.NONE, currentPowerW,
                "margin " + m + "W in deadzone [" + lowMarginW + "," + startMarginW + ") → hold at " + currentPowerW + "W");
    }

    /**
     * Whether the thresholds avoid boundary oscillation: a single power step
     * (which shifts the margin by {@code stepW}) must not carry the margin from
     * the step-up threshold straight past the step-down threshold, i.e. the
     * deadzone must be at least one step wide.
     */
    public static boolean isStableConfig(int startMarginW, int lowMarginW, int stepW) {
        return (startMarginW - lowMarginW) >= stepW;
    }
}
