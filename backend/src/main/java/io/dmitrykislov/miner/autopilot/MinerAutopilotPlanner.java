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
 *   <li>Miner on &amp; margin &lt; {@code lowMarginW} → <b>STEP_DOWN</b> — by at least
 *       one {@code stepW}, but further if a single step wouldn't bring the target
 *       under the <em>available surplus</em> ({@code margin + currentPowerW}, i.e.
 *       solar − base-house load). This guarantees the miner never draws more than
 *       the surplus, even on a sudden solar drop. If even {@code minPowerW} would
 *       exceed the surplus → <b>STOP</b>.</li>
 *   <li>Otherwise (deadzone {@code [lowMarginW, startMarginW)}) → <b>NONE</b> (hold).</li>
 * </ul>
 *
 * <p><b>Invariant:</b> any decision that leaves the miner running sets a power ≤ the
 * available surplus — the miner never imports from the grid. The constructor enforces
 * the two preconditions this depends on ({@code startMarginW ≥ minPowerW} and
 * {@code stepW ≤ startMarginW}); {@link #isStableConfig} additionally flags a config
 * whose deadzone is narrower than a step (prone to start/stop oscillation).
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
        if (stepW <= 0) {
            throw new IllegalArgumentException("stepW must be > 0");
        }
        // Preconditions that make the "never import" invariant hold (see class javadoc):
        // starting draws minPowerW, so the start margin must cover it; and a step-up
        // fires at margin ≥ startMarginW yet adds stepW of draw, so a step can't exceed it.
        if (startMarginW < minPowerW) {
            throw new IllegalArgumentException("startMarginW (" + startMarginW + ") must be ≥ minPowerW ("
                    + minPowerW + "): starting the miner would otherwise import from the grid");
        }
        if (stepW > startMarginW) {
            throw new IllegalArgumentException("stepW (" + stepW + ") must be ≤ startMarginW ("
                    + startMarginW + "): a step-up would otherwise import from the grid");
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
            // Already at the floor → can't reduce further → stop.
            if (currentPowerW <= minPowerW) {
                return AutopilotDecision.of(Action.STOP, 0,
                        "margin " + m + "W < " + lowMarginW + "W and at floor " + minPowerW + "W → stop miner");
            }
            // Surplus available to the miner = margin + its own current draw (the meter
            // counts the miner, so adding its draw back yields solar − base-house load).
            double surplusW = marginW + currentPowerW;
            long s = Math.round(surplusW);
            // If even the floor would draw more than the surplus, running at all imports
            // from the grid → stop.
            if (surplusW < minPowerW) {
                return AutopilotDecision.of(Action.STOP, 0,
                        "margin " + m + "W < " + lowMarginW + "W, surplus " + s
                                + "W below floor " + minPowerW + "W → stop miner");
            }
            // Reduce by at least one step, but further if a single step wouldn't bring
            // the target under the surplus (keeping a lowMarginW buffer). Clamped to the
            // floor. The target therefore never exceeds the surplus → never imports.
            int fitTarget = (int) Math.floor(surplusW) - lowMarginW;
            int next = Math.max(minPowerW, Math.min(currentPowerW - stepW, fitTarget));
            return AutopilotDecision.of(Action.STEP_DOWN, next,
                    "margin " + m + "W < " + lowMarginW + "W, surplus " + s + "W → down to " + next + "W");
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
