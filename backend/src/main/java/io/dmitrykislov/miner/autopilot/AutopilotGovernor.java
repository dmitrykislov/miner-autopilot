package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.autopilot.AutopilotDecision.Action;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * The smoothed, safety-first autopilot decision engine. Pure and clock-injected — every
 * input (including {@code now}) is passed in — so it is exhaustively unit-testable with no
 * real time, no I/O, and no beans.
 *
 * <p>It drives the miner across a fixed <b>power ladder</b> ({@code floor, floor+step, … , ceil})
 * to track the sustained solar surplus, using <b>time-averaged</b> signals so brief clouds are
 * ridden through. Design goals: <i>safe</i> (never targets above the available surplus → never
 * imports; fast, dampening-free response down/off on a sustained drop), <i>smooth</i> (gentle,
 * rung-quantized ramp-up with a min interval; hysteresis so it can't flap), and <i>miner-friendly</i>
 * (discrete pre-tunable levels rather than continuous re-tuning).
 *
 * <p>Available surplus {@code S = avg(margin) + currentPower} (= solar − base-house load, which
 * is miner-independent). Target for a window = highest ladder rung ≤ {@code S − headroom}; because
 * {@code headroom > 0} the target is always strictly below the surplus, so a running miner never
 * imports by construction.
 *
 * <p>Cadence is deliberately asymmetric: ramp <b>up</b> slowly (long window, {@code upInterval},
 * capped rungs/cycle) only on well-established surplus; step <b>down</b>/<b>stop</b> quickly (short
 * window, shorter {@code downInterval}, uncapped, with an emergency bypass) to protect against import.
 * The invariant {@code interval ≥ its window} guarantees the average is never contaminated by a
 * just-made power change.
 */
public final class AutopilotGovernor {

    /**
     * @param floorW        lowest power the autopilot will run the miner at; below this → stop
     * @param ceilW         highest power (hardware/user ceiling)
     * @param stepW         ladder rung spacing
     * @param headroomW     surplus kept unused (anti-import buffer); target = S − headroom
     * @param startSurplusW surplus (long-window) required to (re)start from off — must be > floor
     *                      so start/stop have hysteresis and can't flap
     * @param upInterval    min time between up/start commands (dampening); must be ≥ longWindow
     * @param downInterval  min time between routine down commands (protection can bypass it)
     * @param longWindow    averaging window used for up/start; also the "mined long enough" guard
     * @param upMaxRungsPerCycle cap on how many rungs a single up-move may climb (smooth ramp)
     * @param emergencyGapW if the miner is over-drawing the surplus by at least this, a down-step
     *                      bypasses {@code downInterval}
     */
    public record Config(int floorW, int ceilW, int stepW, int headroomW, int startSurplusW,
                         Duration upInterval, Duration downInterval, Duration longWindow,
                         int upMaxRungsPerCycle, int emergencyGapW) {
        public Config {
            if (floorW <= 0) throw new IllegalArgumentException("floorW must be > 0");
            if (ceilW <= floorW) throw new IllegalArgumentException("ceilW must be > floorW");
            if (stepW <= 0) throw new IllegalArgumentException("stepW must be > 0");
            if (headroomW < 0) throw new IllegalArgumentException("headroomW must be ≥ 0");
            if (startSurplusW <= floorW) {
                throw new IllegalArgumentException("startSurplusW must be > floorW (start/stop hysteresis)");
            }
            if (upMaxRungsPerCycle < 1) throw new IllegalArgumentException("upMaxRungsPerCycle must be ≥ 1");
            if (emergencyGapW <= 0) throw new IllegalArgumentException("emergencyGapW must be > 0");
            if (isNonPositive(upInterval) || isNonPositive(downInterval) || isNonPositive(longWindow)) {
                throw new IllegalArgumentException("intervals and longWindow must be positive");
            }
            if (upInterval.compareTo(longWindow) < 0) {
                throw new IllegalArgumentException("upInterval must be ≥ longWindow (avoid stale-average contamination)");
            }
        }

        private static boolean isNonPositive(Duration d) {
            return d == null || d.isNegative() || d.isZero();
        }
    }

    /**
     * A decision-time snapshot. {@code shortMarginW}/{@code longMarginW} are the averaged
     * (solar − house) margins in watts, empty when unavailable/stale.
     *
     * @param currentPowerW the miner's current power target (may be null/off-grid; treated as
     *                      floor when running and null)
     * @param miningSince   when the miner began <em>continuously</em> mining (null if not mining)
     * @param lastChangeAt  when the autopilot last changed the miner (null if never)
     */
    public record Input(Instant now, boolean reachable, boolean running, boolean suspended,
                        Integer currentPowerW, Instant miningSince, Instant lastChangeAt,
                        OptionalDouble shortMarginW, OptionalDouble longMarginW) {}

    private final Config cfg;
    private final int[] rungs;

    public AutopilotGovernor(Config cfg) {
        this.cfg = cfg;
        this.rungs = buildLadder(cfg.floorW(), cfg.ceilW(), cfg.stepW());
    }

    /** The power ladder (ascending), for display/tests. */
    public int[] ladder() {
        return rungs.clone();
    }

    public AutopilotDecision decide(Input in) {
        if (!in.reachable()) return none("miner unreachable — skipping");
        if (in.suspended()) return none("miner suspended (draw not in margin) — skipping");

        int cur = in.running() ? (in.currentPowerW() != null ? in.currentPowerW() : cfg.floorW()) : 0;

        // No fresh recent data → we're blind. A running miner is stopped for safety; an off one holds.
        if (in.shortMarginW().isEmpty()) {
            return in.running()
                    ? decision(Action.STOP, 0, "no fresh solar/consumption data — stopping for safety")
                    : none("no fresh data and miner off — nothing to do");
        }
        double sShort = in.shortMarginW().getAsDouble() + cur;

        // ---- protection (bypasses the up dampening) ----
        if (in.running()) {
            // 1) can't even sustain the floor → stop now.
            if (sShort - cfg.headroomW() < cfg.floorW()) {
                return decision(Action.STOP, 0, String.format(
                        "surplus %dW can't hold floor %dW → stop", Math.round(sShort), cfg.floorW()));
            }
            // 2) over-drawing the surplus by ≥ a rung → step down toward what it can hold.
            int downTarget = rungAtOrBelow(sShort - cfg.headroomW());
            if (downTarget < cur) {
                boolean emergency = (cur - sShort) >= cfg.emergencyGapW();
                if (emergency || elapsed(in.lastChangeAt(), in.now(), cfg.downInterval())) {
                    return decision(Action.STEP_DOWN, downTarget, String.format(
                            "surplus %dW%s → down to %dW", Math.round(sShort),
                            emergency ? " (importing hard)" : "", downTarget));
                }
                return none(String.format("surplus dropped to %dW but within down-interval → holding at %dW",
                        Math.round(sShort), cur));
            }
        }

        // ---- optimization (dampened) ----
        if (!elapsed(in.lastChangeAt(), in.now(), cfg.upInterval())) {
            return none("within up dampening window → holding");
        }
        if (in.longMarginW().isEmpty()) {
            return none("no long-window average yet → holding");
        }
        double sLong = in.longMarginW().getAsDouble() + cur;

        if (!in.running()) {
            if (sLong >= cfg.startSurplusW()) {
                return decision(Action.START, cfg.floorW(), String.format(
                        "surplus %dW ≥ start %dW → start at floor %dW",
                        Math.round(sLong), cfg.startSurplusW(), cfg.floorW()));
            }
            return none(String.format("surplus %dW < start %dW → stay off", Math.round(sLong), cfg.startSurplusW()));
        }

        // running: ramp up, but only once we've mined long enough for the long average to be valid,
        // and only by a bounded number of rungs per cycle.
        if (!minedLongEnough(in)) {
            return none("mining not long enough for a valid up-average → holding");
        }
        int upTarget = rungAtOrBelow(sLong - cfg.headroomW());
        if (upTarget > cur) {
            int capped = rungAtOrBelow(Math.min(upTarget, cur + cfg.upMaxRungsPerCycle() * cfg.stepW()));
            if (capped > cur) {
                return decision(Action.STEP_UP, capped, String.format(
                        "surplus %dW → up to %dW", Math.round(sLong), capped));
            }
        }
        return none(String.format("surplus %dW, holding at %dW", Math.round(sLong), cur));
    }

    // ---- helpers ------------------------------------------------------------

    private boolean minedLongEnough(Input in) {
        return in.running() && in.miningSince() != null
                && Duration.between(in.miningSince(), in.now()).compareTo(cfg.longWindow()) >= 0;
    }

    private static boolean elapsed(Instant last, Instant now, Duration interval) {
        return last == null || Duration.between(last, now).compareTo(interval) >= 0;
    }

    /** Highest ladder rung ≤ {@code watts}; if below the floor, returns {@code floorW − 1} (a stop signal). */
    private int rungAtOrBelow(double watts) {
        if (watts < cfg.floorW()) return cfg.floorW() - 1;
        int chosen = rungs[0];
        for (int r : rungs) {
            if (r <= watts) chosen = r; else break;
        }
        return chosen;
    }

    private static int[] buildLadder(int floor, int ceil, int step) {
        int n = (ceil - floor) / step + 1;
        boolean ceilOnGrid = (ceil - floor) % step == 0;
        int len = ceilOnGrid ? n : n + 1;
        int[] out = new int[len];
        for (int i = 0; i < n; i++) out[i] = floor + i * step;
        if (!ceilOnGrid) out[len - 1] = ceil;
        return out;
    }

    private static AutopilotDecision none(String reason) {
        return decision(Action.NONE, 0, reason);
    }

    private static AutopilotDecision decision(Action a, int target, String reason) {
        return AutopilotDecision.of(a, target, "autopilot: " + reason);
    }
}
