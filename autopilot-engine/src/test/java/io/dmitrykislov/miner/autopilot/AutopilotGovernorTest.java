package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.autopilot.AutopilotDecision.Action;
import io.dmitrykislov.miner.autopilot.AutopilotGovernor.Config;
import io.dmitrykislov.miner.autopilot.AutopilotGovernor.Input;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive tests for the ladder controller. Config: floor 1200, ceil 3600, step 400 →
 * ladder [1200,1600,2000,2400,2800,3200,3600]; headroom 200; start 1600; up 15 min (≤2 rungs);
 * down 5 min; emergency gap 800. The Input carries the miner-independent surplus S directly
 * (EnergyAverages already adds the miner's own draw back); currentPowerW is used only for the
 * rung / over-draw comparisons, never to reconstruct S.
 */
class AutopilotGovernorTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofHours(1));   // intervals elapsed
    private static final Instant RECENT = NOW.minus(Duration.ofMinutes(1));   // within intervals
    private static final Instant MINED_LONG = NOW.minus(Duration.ofMinutes(20)); // ≥ longWindow(15)
    private static final Instant MINED_SHORT = NOW.minus(Duration.ofMinutes(5)); // < longWindow

    private final Config cfg = new Config(1200, 3600, 400, 200, 1600,
            Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800);
    private final AutopilotGovernor gov = new AutopilotGovernor(cfg);

    // Same config but with a 3-minute minimum run-time (the min-run guard enabled).
    private final AutopilotGovernor govMin = new AutopilotGovernor(new Config(1200, 3600, 400, 200, 1600,
            Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800, Duration.ofMinutes(3)));

    /** Running miner at {@code cur} W with available (miner-independent) surplus {@code S}. */
    private Input running(int cur, double S, Instant lastChange, Instant miningSince) {
        return new Input(NOW, true, true, false, cur, miningSince, lastChange,
                true, OptionalDouble.of(S), OptionalDouble.of(S));
    }

    /** Off miner with available surplus {@code S}. */
    private Input off(double S, Instant lastChange) {
        return new Input(NOW, true, false, false, null, null, lastChange,
                true, OptionalDouble.of(S), OptionalDouble.of(S));
    }

    /** Off miner with <em>divergent</em> short/long surpluses (for the restart confirmation guard). */
    private Input offSL(double sShort, double sLong, Instant lastChange) {
        return new Input(NOW, true, false, false, null, null, lastChange,
                true, OptionalDouble.of(sShort), OptionalDouble.of(sLong));
    }

    /** Running miner with <em>divergent</em> short- and long-window available surpluses. */
    private Input runningSL(int cur, double sShort, double sLong, Instant lastChange, Instant miningSince) {
        return new Input(NOW, true, true, false, cur, miningSince, lastChange,
                true, OptionalDouble.of(sShort), OptionalDouble.of(sLong));
    }

    // ---------------------------------------------------------------- guards
    /** An unreachable miner (e.g. a stopped Braiins miner: API "unavailable") — treated as OFF. */
    private Input unreachable(double S, Instant lastChange) {
        return new Input(NOW, false, false, false, null, null, lastChange,
                true, OptionalDouble.of(S), OptionalDouble.of(S));
    }

    @Test void unreachableMinerWithSurplusIsRestarted() {
        // The recovery case: the autopilot stopped it (→ unreachable), the surplus later returns.
        var d = gov.decide(unreachable(2000, LONG_AGO)); // ≥ start 1600
        assertThat(d.action()).isEqualTo(Action.START);
        assertThat(d.targetPowerW()).isEqualTo(1200);       // (re)start at the floor
        assertThat(d.reason()).contains("restart");
    }

    @Test void unreachableMinerWithoutSurplusStaysOff() {
        var d = gov.decide(unreachable(1500, LONG_AGO));    // < start 1600
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("stay off");
    }

    @Test void unreachableMinerWithinRestartCooldownWaits() {
        // Anti-cycling: don't relaunch immediately after a recent change even with surplus. Restart is
        // gated by the SHORT downInterval cooldown (5 min), not the long up-interval — RECENT (1 min)
        // is inside it.
        var d = gov.decide(unreachable(2000, RECENT));
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("restart cooldown");
    }

    @Test void unreachableMinerWithStaleFeedDoesNothing() {
        Input in = new Input(NOW, false, false, false, null, null, LONG_AGO,
                false, OptionalDouble.empty(), OptionalDouble.empty()); // no surplus data
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
    }

    @Test void suspendedSkips() {
        Input in = new Input(NOW, true, true, true, 3600, MINED_LONG, LONG_AGO,
                true, OptionalDouble.of(2000), OptionalDouble.of(2000));
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
        assertThat(gov.decide(in).reason()).contains("suspended");
    }

    @Test void staleFeedStopsRunningMiner() {
        Input in = new Input(NOW, true, true, false, 3600, MINED_LONG, LONG_AGO,
                false, OptionalDouble.empty(), OptionalDouble.empty()); // dataFresh = false
        assertThat(gov.decide(in).action()).isEqualTo(Action.STOP);
        assertThat(gov.decide(in).reason()).contains("no fresh");
    }

    @Test void staleFeedLeavesOffMinerAlone() {
        Input in = new Input(NOW, true, false, false, null, null, LONG_AGO,
                false, OptionalDouble.empty(), OptionalDouble.empty());
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
    }

    @Test void freshButSparseHoldsRunningMinerInsteadOfStopping() {
        // Just booted: feed is fresh but the short window isn't covered yet → hold, don't stop.
        Input in = new Input(NOW, true, true, false, 3600, MINED_SHORT, LONG_AGO,
                true, OptionalDouble.empty(), OptionalDouble.empty());
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
        assertThat(gov.decide(in).reason()).contains("insufficient recent data");
    }

    @Test void freshButSparseLeavesOffMinerOff() {
        Input in = new Input(NOW, true, false, false, null, null, LONG_AGO,
                true, OptionalDouble.empty(), OptionalDouble.empty());
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
    }

    // ---------------------------------------------------------------- start
    @Test void startsAtFloorWhenSurplusAboveStartThreshold() {
        var d = gov.decide(off(1600, LONG_AGO));   // exactly the start threshold
        assertThat(d.action()).isEqualTo(Action.START);
        assertThat(d.targetPowerW()).isEqualTo(1200);   // always starts at the floor
    }

    @Test void doesNotStartBelowStartThreshold() {
        assertThat(gov.decide(off(1599, LONG_AGO)).action()).isEqualTo(Action.NONE);
        assertThat(gov.decide(off(1599, LONG_AGO)).reason()).contains("stay off");
    }

    @Test void doesNotStartWithinRestartCooldown() {
        var d = gov.decide(off(5000, RECENT)); // huge surplus but only 1 min since the last change
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("restart cooldown");
    }

    @Test void startAllowedWhenNeverChanged() {
        assertThat(gov.decide(off(2000, null)).action()).isEqualTo(Action.START); // null lastChange = elapsed
    }

    @Test void restartAllowedAfterDownIntervalEvenWhileWithinUpInterval() {
        // The bug this fixes: a stopped miner was blocked from restarting for the whole 15-min
        // up-interval, stranding a returned surplus off-grid. Restart must be allowed once the SHORT
        // 5-min cooldown has passed, even though the 15-min up-interval has NOT.
        var eightMinAgo = NOW.minus(Duration.ofMinutes(8)); // > downInterval(5), < upInterval(15)
        var d = gov.decide(off(5000, eightMinAgo));
        assertThat(d.action()).isEqualTo(Action.START);
        assertThat(d.targetPowerW()).isEqualTo(1200);
    }

    @Test void restartAllowedExactlyAtDownIntervalBoundary() {
        var d = gov.decide(off(2000, NOW.minus(Duration.ofMinutes(5)))); // == downInterval → elapsed
        assertThat(d.action()).isEqualTo(Action.START);
    }

    @Test void restartBlockedWhenShortWindowCannotYetHoldFloor() {
        // Anti-flap confirmation: long surplus is well above start, but the recent (short) surplus
        // can't cover floor+headroom yet — restarting now would trip an immediate protective stop.
        var d = gov.decide(offSL(1300, 2500, LONG_AGO)); // sShort−headroom=1100 < floor 1200
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("can't yet hold floor");
    }

    @Test void restartProceedsOnceShortWindowConfirmsFloorIsSustainable() {
        var d = gov.decide(offSL(1500, 2500, LONG_AGO)); // sShort−headroom=1300 ≥ floor 1200
        assertThat(d.action()).isEqualTo(Action.START);
    }

    // ---------------------------------------------------------------- step up
    @Test void rampsUpCappedToTwoRungs() {
        var d = gov.decide(running(1200, 3800, LONG_AGO, MINED_LONG)); // could target 3600
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
        assertThat(d.targetPowerW()).isEqualTo(2000);   // 1200 + 2 rungs, not straight to 3600
    }

    @Test void holdsAtMax() {
        var d = gov.decide(running(3600, 5000, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test void deadbandHoldsWhenNextRungNotReached() {
        // at 2000 W, surplus 2300 → S−headroom=2100 → still rung 2000 → hold (no 50–100 W chasing)
        var d = gov.decide(running(2000, 2300, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test void doesNotRampUpUntilMinedLongEnough() {
        var d = gov.decide(running(1200, 3800, LONG_AGO, MINED_SHORT));
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("mining not long enough");
    }

    @Test void doesNotRampUpWithoutLongWindowAverage() {
        Input in = new Input(NOW, true, true, false, 1200, MINED_LONG, LONG_AGO,
                true, OptionalDouble.of(3800), OptionalDouble.empty()); // short surplus only
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
        assertThat(gov.decide(in).reason()).contains("long-window");
    }

    // ---------------------------------------------------------------- step down
    @Test void stepsDownToTheRungSurplusSupports() {
        // The scenario: 3600 W and margin −1800 → S=1800 → rung 1600.
        var d = gov.decide(running(3600, 1800, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(1600);
    }

    @Test void stepsDownOneRungOnMildDecline() {
        var d = gov.decide(running(2400, 2300, LONG_AGO, MINED_LONG)); // S−headroom=2100 → rung 2000
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(2000);
    }

    @Test void downStepWaitsForIntervalWhenNotEmergency() {
        var d = gov.decide(running(2400, 2100, RECENT, MINED_LONG)); // gap 300 < emergency 800
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("down-interval");
    }

    @Test void emergencyDownBypassesIntervalWhenImportingHard() {
        // 3600 W, S=1800 → over-drawing by 1800 ≥ emergency gap 800 → act despite recent change
        var d = gov.decide(running(3600, 1800, RECENT, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(1600);
        assertThat(d.reason()).contains("importing hard");
    }

    // ---------------------------------------------------------------- stop
    @Test void stopsWhenSurplusCannotHoldFloor() {
        var d = gov.decide(running(1600, 1300, LONG_AGO, MINED_LONG)); // S−headroom=1100 < 1200
        assertThat(d.action()).isEqualTo(Action.STOP);
        assertThat(d.reason()).contains("floor");
    }

    @Test void stopBypassesDownIntervalAndDampening() {
        var d = gov.decide(running(2000, 1000, RECENT, MINED_LONG)); // just changed, but must stop
        assertThat(d.action()).isEqualTo(Action.STOP);
    }

    // ---------------------------------------------- start/stop hysteresis (no flap)
    @Test void runningMinerHoldsInTheHysteresisBand() {
        // S=1500 is above the 1200 stop point but below the 1600 start point.
        var d = gov.decide(running(1200, 1500, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE); // keep running at the floor
    }

    @Test void offMinerStaysOffInTheHysteresisBand() {
        assertThat(gov.decide(off(1500, LONG_AGO)).action()).isEqualTo(Action.NONE); // below start(1600)
    }

    // ---------------------------------------------------------------- edge cases
    @Test void subFloorRunningMinerIsRaisedTowardTheFloor() {
        // running at 800 (below floor) with surplus → step up ≤2 rungs: 800 + 2·400 = 1600.
        var d = gov.decide(running(800, 3000, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
        assertThat(d.targetPowerW()).isEqualTo(1600);
    }

    @Test void subFloorRunningMinerIsNeverSteppedToAnOffLadderSubFloorTarget() {
        // Running below the floor (1100). Short surplus is high enough to avoid a stop
        // (1500−200=1300 ≥ 1200), but the long surplus can only support below the floor
        // (1300−200=1100 < 1200), so the up-ramp target computes to the sub-floor sentinel (1199).
        // It must NOT command 1199 (off-ladder, below the floor) — hold instead.
        var d = gov.decide(runningSL(1100, 1500, 1300, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test void nullCurrentPowerWhileRunningTreatedAsFloor() {
        Input in = new Input(NOW, true, true, false, null, MINED_LONG, LONG_AGO,
                true, OptionalDouble.of(1600), OptionalDouble.of(1600)); // surplus 1600, cur→floor 1200
        // Deterministic: S−headroom = 1400 → highest rung ≤ 1400 is the floor 1200 = cur → hold.
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
    }

    // ---------------------------------------------- import invariants (property)
    // Fully settled (down-interval elapsed): the resulting draw never exceeds the surplus at all.
    @Test void aSettledRunningMinerIsNeverTargetedAboveTheSurplus() {
        int[] surpluses = {1000, 1300, 1500, 1800, 2200, 2600, 3000, 3400, 3800, 5000};
        for (int cur : gov.ladder()) {
            for (int S : surpluses) {
                var d = gov.decide(running(cur, S, LONG_AGO, MINED_LONG)); // LONG_AGO → down never throttled
                Integer runPower = switch (d.action()) {
                    case START, STEP_UP, STEP_DOWN -> d.targetPowerW();
                    case NONE -> cur;    // holding at the current draw
                    case STOP -> null;   // off → draws nothing
                };
                if (runPower != null) {
                    assertThat((double) runPower)
                            .as("cur=%d S=%d action=%s target=%d", cur, S, d.action(), d.targetPowerW())
                            .isLessThanOrEqualTo(S); // never draws more than the available surplus
                }
            }
        }
    }

    // Within the down-interval (recent change): the guarantee is BOUNDED, not absolute — a held
    // miner over-draws the surplus by strictly less than emergencyGapW (a larger over-draw would
    // have bypassed the interval and stepped down). This is the intended "absorb minor dips" band.
    @Test void aThrottledRunningMinerNeverOverDrawsBeyondTheEmergencyGap() {
        int[] surpluses = {800, 1000, 1300, 1500, 1600, 1800, 2000, 2200, 2600, 3000, 3400, 3800, 5000};
        for (int cur : gov.ladder()) {
            for (int S : surpluses) {
                var d = gov.decide(running(cur, S, RECENT, MINED_LONG)); // RECENT → down throttled
                if (d.action() == Action.NONE) { // held at cur
                    assertThat((double) (cur - S))
                            .as("held at cur=%d with surplus=%d", cur, S)
                            .isLessThan(cfg.emergencyGapW());
                }
            }
        }
    }

    // ------------------------------------------- divergent short vs long windows
    @Test void downProtectionUsesShortWindowEvenWhenLongIsStillHigh() {
        // Recent dip: short surplus 2300 → step down to 2000, even though the long average (3600)
        // would say "ramp up". Protection must react to the short window.
        var d = gov.decide(runningSL(2400, 2300, 3600, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(2000);
    }

    @Test void upRampIgnoresAShortSpikeAndUsesLongWindow() {
        // Brief surplus spike on the short window (3600) but the long average is only 1600.
        // The governor must NOT chase the spike — it ramps on the long window → holds.
        var d = gov.decide(runningSL(1600, 3600, 1600, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
    }

    @Test void upRampFollowsLongWindowWhenShortIsLower() {
        // Short (2000) below long (3000): down-check off short holds (2000−200→rung 1600 < cur? no,
        // cur is 1600)… actually cur 1600, short surplus 2000 supports staying; long 3000 drives up.
        var d = gov.decide(runningSL(1600, 2000, 3000, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
        assertThat(d.targetPowerW()).isEqualTo(2400); // long 3000−200→2800 rung, capped to +2 rungs = 2400
    }

    // ------------------------------------------- emergency-gap boundary (>= gap)
    @Test void emergencyBypassAtExactlyTheGap() {
        // cur 2400, short surplus 1600 → over-drawing by exactly 800 (== emergencyGap) → bypass interval.
        var d = gov.decide(runningSL(2400, 1600, 1600, RECENT, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(1200);
        assertThat(d.reason()).contains("importing hard");
    }

    @Test void noEmergencyJustBelowTheGapWaitsForInterval() {
        // over-drawing by 799 (< 800) and within the down-interval → hold.
        var d = gov.decide(runningSL(2400, 1601, 1601, RECENT, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("down-interval");
    }

    // ------------------------------------------- stop boundary (S−headroom vs floor)
    @Test void exactlyFloorPlusHeadroomKeepsMinerAtFloorNotStopped() {
        // S=1400 → S−headroom = 1200 == floor (not < floor) → don't stop; step to the floor.
        var d = gov.decide(running(1600, 1400, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
        assertThat(d.targetPowerW()).isEqualTo(1200);
    }

    @Test void oneWattBelowFloorPlusHeadroomStops() {
        // S=1399 → S−headroom = 1199 < floor 1200 → stop.
        var d = gov.decide(running(1600, 1399, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STOP);
    }

    // ------------------------------------------- up dampening for a running miner
    @Test void runningMinerUpRampBlockedInsideUpInterval() {
        // Wants to climb (surplus 3800) but changed 1 min ago (< 15 min up-interval) → hold.
        var d = gov.decide(running(1200, 3800, RECENT, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("up dampening");
    }

    @Test void rampsUpSingleRungWhenThatIsAllTheCapAllows() {
        // surplus 2600 → long 2400 rung is exactly one rung above cur 2000 (below the 2-rung cap).
        var d = gov.decide(running(2000, 2600, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
        assertThat(d.targetPowerW()).isEqualTo(2400);
    }

    // ---------------------------------------------------------------- min run-time
    @Test void holdsThroughAMildDipWithinMinRunTime() {
        // Mining for 1 min (< 3 min min-run). Surplus dipped so it can't hold the floor, but it's not
        // a hard import → ride it out instead of cycling the miner off so soon after starting.
        var d = govMin.decide(running(1200, 1300, LONG_AGO, RECENT)); // 1300−200 = 1100 < floor 1200
        assertThat(d.action()).isEqualTo(Action.NONE);
        assertThat(d.reason()).contains("min run-time");
    }

    @Test void stopsWithinMinRunTimeWhenImportingHard() {
        // Even inside the min-run window, a hard import (over-draw ≥ emergencyGap 800) stops at once.
        var d = govMin.decide(running(1200, 350, LONG_AGO, RECENT)); // 1200 − 350 = 850 ≥ 800
        assertThat(d.action()).isEqualTo(Action.STOP);
    }

    @Test void stopsForAMildDipOnceMinRunTimeHasElapsed() {
        // Mining 20 min (> 3 min): the guard no longer applies → a floor-breaching dip stops it.
        var d = govMin.decide(running(1200, 1300, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STOP);
    }

    @Test void minRunTimeDisabledStopsImmediately() {
        // Default config (minRunTime = 0): the guard is off, so the same mild dip stops right away.
        var d = gov.decide(running(1200, 1300, LONG_AGO, RECENT));
        assertThat(d.action()).isEqualTo(Action.STOP);
    }

    // ------------------------------------------- interval boundary (elapsed is inclusive)
    @Test void upStepAllowedExactlyAtTheUpIntervalBoundary() {
        // A RUNNING miner's up-step is gated by the 15-min up-interval; exactly at the boundary → allowed.
        var d = gov.decide(running(1200, 3800, NOW.minus(Duration.ofMinutes(15)), MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
    }

    // ---------------------------------------------------------------- ladder + config
    @Test void ladderIsFloorToCeilByStep() {
        assertThat(gov.ladder()).containsExactly(1200, 1600, 2000, 2400, 2800, 3200, 3600);
    }

    @Test void ladderIncludesAnOffGridCeiling() {
        var g = new AutopilotGovernor(new Config(1200, 3500, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800));
        assertThat(g.ladder()).containsExactly(1200, 1600, 2000, 2400, 2800, 3200, 3500);
    }

    @Test void rejectsInvalidConfig() {
        // floor ≤ 0
        assertThatThrownBy(() -> new Config(0, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // ceil ≤ floor
        assertThatThrownBy(() -> new Config(3600, 1200, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // start ≤ floor (no hysteresis)
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1200,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // start below the stop threshold (floor+headroom=1400) → start/stop churn
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1300,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // upInterval < longWindow (contamination)
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // step ≤ 0
        assertThatThrownBy(() -> new Config(1200, 3600, 0, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // headroom < 0
        assertThatThrownBy(() -> new Config(1200, 3600, 400, -1, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // emergencyGap ≤ 0
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // upMaxRungsPerCycle < 1
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 0, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // non-positive downInterval
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ZERO, Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // non-positive longWindow
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofSeconds(-1), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
        // non-positive upInterval
        assertThatThrownBy(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsAValidConfig() {
        assertThatCode(() -> new Config(1200, 3600, 400, 200, 1600,
                Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofMinutes(15), 2, 800))
                .doesNotThrowAnyException();
    }
}
