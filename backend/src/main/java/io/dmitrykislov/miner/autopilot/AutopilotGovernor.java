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
 * ridden through. Design goals: <i>safe</i> (every command targets below the available surplus, and
 * a sustained over-draw is corrected quickly), <i>smooth</i> (gentle, rung-quantized ramp-up with a
 * min interval; hysteresis so it can't flap), and <i>miner-friendly</i> (discrete pre-tunable levels
 * rather than continuous re-tuning).
 *
 * <p>Available surplus {@code S = avg(margin) + currentPower} (= solar − base-house load, which
 * is miner-independent). Every command sets the target to the highest ladder rung ≤ {@code S − headroom};
 * because {@code headroom > 0} a command never targets at or above the surplus.
 *
 * <p><b>Import bound (not "never" — bounded).</b> Between commands the surplus can drift below the
 * miner's draw. A routine down-step is throttled by {@code downInterval} to avoid chasing noise, so a
 * <em>mild</em> over-draw is tolerated transiently — this is the intended "ignore minor dips, let the
 * grid/battery absorb them" behaviour. The bound is deliberate: an over-draw of at least
 * {@code emergencyGapW} bypasses {@code downInterval} and steps down immediately, so the miner never
 * over-draws the (short-window) surplus by {@code ≥ emergencyGapW}, and any smaller over-draw lasts at
 * most one {@code downInterval}. Tighten {@code emergencyGapW}/{@code downInterval} for stricter import
 * control at the cost of more frequent power changes.
 *
 * <p>Cadence is deliberately asymmetric: ramp <b>up</b> slowly (long window, {@code upInterval},
 * capped rungs/cycle) only on well-established surplus; step <b>down</b>/<b>stop</b> quickly (short
 * window, shorter {@code downInterval}, uncapped, with the emergency bypass above). The invariant
 * {@code interval ≥ its window} (up: {@code upInterval ≥ longWindow}; down: {@code downInterval ≥
 * shortWindow}, enforced at config binding) guarantees the average driving a change is never
 * contaminated by the previous change in that direction.
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
            // The start threshold must sit at or above the stop threshold (floor+headroom): a miner
            // running at the floor STOPs when surplus < floor+headroom, so if it started below that
            // it would start then immediately stop — perpetual start/stop churn.
            if (startSurplusW < floorW + headroomW) {
                throw new IllegalArgumentException("startSurplusW (" + startSurplusW
                        + ") must be ≥ floorW + headroomW (" + (floorW + headroomW)
                        + "): otherwise the miner would start below the stop threshold and churn");
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
     * A decision-time snapshot. {@code shortSurplusW}/{@code longSurplusW} are the averaged
     * <b>miner-independent</b> surplus in watts (= avg(solar − base-house-load), the miner's own
     * draw already added back by {@link EnergyAverages}), empty when unavailable/stale. Because the
     * surplus already accounts for the miner, it must NOT have {@code currentPowerW} added again.
     *
     * @param currentPowerW the miner's current power target (may be null/off-grid; treated as
     *                      floor when running and null). Used only to pick the rung to move to and
     *                      to gauge over-draw — never to reconstruct the surplus.
     * @param miningSince   when the miner began <em>continuously</em> mining (null if not mining)
     * @param lastChangeAt  when the autopilot last changed the miner (null if never)
     * @param dataFresh     both energy feeds have a recent sample. Distinguishes a <em>stale</em>
     *                      feed (blind → stop) from a merely <em>sparse</em> window (an empty
     *                      surplus while fresh → hold, e.g. right after boot)
     */
    public record Input(Instant now, boolean reachable, boolean running, boolean suspended,
                        Integer currentPowerW, Instant miningSince, Instant lastChangeAt,
                        boolean dataFresh, OptionalDouble shortSurplusW, OptionalDouble longSurplusW) {}

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
        if (in.suspended()) return none("miner suspended (draw not in margin) — skipping");

        // An UNREACHABLE miner is treated as OFF. A Braiins miner whose BOSMiner service is stopped
        // reports its GraphQL as "unavailable", i.e. unreachable — but it can still be (re)started
        // (the start command targets the service manager). We can't step or stop something we can't
        // reach, so the only action for an unreachable miner is the START path below — this is how it
        // recovers after the autopilot stopped it and the surplus later returns.
        boolean running = in.reachable() && in.running();
        int cur = running ? (in.currentPowerW() != null ? in.currentPowerW() : cfg.floorW()) : 0;

        // Blind: a feed has gone stale/dead → we can't know the surplus. Stop a running miner for
        // safety; leave a not-running one alone.
        if (!in.dataFresh()) {
            return running
                    ? decision(Action.STOP, 0, "no fresh solar/consumption data — stopping for safety")
                    : none("no fresh data and miner not running — nothing to do");
        }
        // Fresh feed but the window isn't covered enough yet (e.g. just after boot): don't act on a
        // sparse average — hold so a healthy miner isn't disrupted, and an off one waits for data.
        if (in.shortSurplusW().isEmpty()) {
            return none("insufficient recent data → holding");
        }
        // Surplus is already miner-independent (the miner's own draw was added back when averaged),
        // so it is NOT re-adjusted by cur. cur is used only for the rung/over-draw comparisons below.
        double sShort = in.shortSurplusW().getAsDouble();

        // ---- protection (bypasses the up dampening) ----
        if (running) {
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
        if (in.longSurplusW().isEmpty()) {
            return none("no long-window average yet → holding");
        }
        double sLong = in.longSurplusW().getAsDouble();

        if (!running) {
            if (sLong >= cfg.startSurplusW()) {
                return decision(Action.START, cfg.floorW(), String.format(
                        "surplus %dW ≥ start %dW → %s at floor %dW",
                        Math.round(sLong), cfg.startSurplusW(),
                        in.reachable() ? "start" : "restart (miner unreachable)", cfg.floorW()));
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
