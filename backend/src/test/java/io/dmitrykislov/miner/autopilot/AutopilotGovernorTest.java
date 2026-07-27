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
 * down 5 min; emergency gap 800. Surplus S = margin + currentPower (0 when off).
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

    /** Running miner at {@code cur} W with available surplus {@code S} (both windows = S). */
    private Input running(int cur, double S, Instant lastChange, Instant miningSince) {
        double margin = S - cur;
        return new Input(NOW, true, true, false, cur, miningSince, lastChange,
                true, OptionalDouble.of(margin), OptionalDouble.of(margin));
    }

    /** Off miner with available surplus {@code S}. */
    private Input off(double S, Instant lastChange) {
        return new Input(NOW, true, false, false, null, null, lastChange,
                true, OptionalDouble.of(S), OptionalDouble.of(S));
    }

    /** Running miner with <em>divergent</em> short- and long-window available surpluses. */
    private Input runningSL(int cur, double sShort, double sLong, Instant lastChange, Instant miningSince) {
        return new Input(NOW, true, true, false, cur, miningSince, lastChange,
                true, OptionalDouble.of(sShort - cur), OptionalDouble.of(sLong - cur));
    }

    // ---------------------------------------------------------------- guards
    @Test void unreachableSkips() {
        Input in = new Input(NOW, false, true, false, 2000, MINED_LONG, LONG_AGO,
                true, OptionalDouble.of(0), OptionalDouble.of(0));
        assertThat(gov.decide(in).action()).isEqualTo(Action.NONE);
        assertThat(gov.decide(in).reason()).contains("unreachable");
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

    @Test void doesNotStartWithinDampeningWindow() {
        assertThat(gov.decide(off(5000, RECENT)).action()).isEqualTo(Action.NONE); // huge surplus but too soon
    }

    @Test void startAllowedWhenNeverChanged() {
        assertThat(gov.decide(off(2000, null)).action()).isEqualTo(Action.START); // null lastChange = elapsed
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
                true, OptionalDouble.of(2600), OptionalDouble.empty()); // short only
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
        // running at 800 (below floor) with surplus → step up to reach the floor band
        var d = gov.decide(running(800, 3000, LONG_AGO, MINED_LONG));
        assertThat(d.action()).isEqualTo(Action.STEP_UP);
        assertThat(d.targetPowerW()).isGreaterThanOrEqualTo(1200);
    }

    @Test void nullCurrentPowerWhileRunningTreatedAsFloor() {
        Input in = new Input(NOW, true, true, false, null, MINED_LONG, LONG_AGO,
                true, OptionalDouble.of(400), OptionalDouble.of(400)); // S = 400 + floor(1200) = 1600
        assertThatCode(() -> gov.decide(in)).doesNotThrowAnyException();
        assertThat(gov.decide(in).action()).isIn(Action.NONE, Action.STEP_UP, Action.STEP_DOWN, Action.STOP);
    }

    // ---------------------------------------------- never-import invariant (property)
    @Test void aRunningMinerIsNeverTargetedAboveTheSurplus() {
        int[] surpluses = {1000, 1300, 1500, 1800, 2200, 2600, 3000, 3400, 3800, 5000};
        for (int cur : gov.ladder()) {
            for (int S : surpluses) {
                var d = gov.decide(running(cur, S, LONG_AGO, MINED_LONG));
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

    // ------------------------------------------- interval boundary (elapsed is inclusive)
    @Test void startAllowedExactlyAtTheUpIntervalBoundary() {
        var d = gov.decide(off(2000, NOW.minus(Duration.ofMinutes(15)))); // == upInterval → elapsed
        assertThat(d.action()).isEqualTo(Action.START);
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
