package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.autopilot.AutopilotDecision.Action;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive tests of the pure control logic. Limits: min 800 W, max 3600 W.
 * Thresholds: start/step-up at margin ≥ 1000 W, back off at margin < 100 W,
 * step 1000 W. Deadzone [100, 1000) holds.
 */
class MinerAutopilotPlannerTest {

    // min=800, max=3600, startMargin=1000, low=100, step=1000
    private final MinerAutopilotPlanner planner = new MinerAutopilotPlanner(800, 3600, 1000, 100, 1000);

    private AutopilotDecision off(double marginW) { return planner.decide(marginW, false, 0); }
    private AutopilotDecision on(double marginW, int cur) { return planner.decide(marginW, true, cur); }

    // ---------------------------------------------------------------- 1) START
    @Nested
    class StartMiner {
        @Test void startsAtExactStartThreshold() {
            var d = off(1000);
            assertThat(d.action()).isEqualTo(Action.START);
            assertThat(d.targetPowerW()).isEqualTo(800);            // always starts at the min
        }

        @Test void startsWhenMarginWellAbove_butStillAtMin() {
            assertThat(off(1500).action()).isEqualTo(Action.START);
            assertThat(off(1500).targetPowerW()).isEqualTo(800);
            assertThat(off(9000).targetPowerW()).isEqualTo(800);    // never starts above min
        }

        @Test void doesNotStartJustBelowThreshold() {
            var d = off(999);
            assertThat(d.action()).isEqualTo(Action.NONE);
            assertThat(d.reason()).contains("stay off");
        }

        @Test void doesNotStartWithZeroOrNegativeMargin() {
            assertThat(off(0).action()).isEqualTo(Action.NONE);
            assertThat(off(-500).action()).isEqualTo(Action.NONE);
        }
    }

    // ------------------------------------------------------ 2) UPDATE POWER (up/down)
    @Nested
    class StepUp {
        @Test void stepsUpByOneStepAtThreshold() {
            var d = on(1000, 800);
            assertThat(d.action()).isEqualTo(Action.STEP_UP);
            assertThat(d.targetPowerW()).isEqualTo(1800);
        }

        @Test void stepIsFixedRegardlessOfHowLargeTheMargin() {
            assertThat(on(3000, 800).targetPowerW()).isEqualTo(1800);   // +1000 only, not +margin
            assertThat(on(50000, 1800).targetPowerW()).isEqualTo(2800);
        }

        @Test void capsAtMaxPower() {
            assertThat(on(1000, 2600).targetPowerW()).isEqualTo(3600);  // 2600+1000 = 3600 exactly
            assertThat(on(1000, 3100).targetPowerW()).isEqualTo(3600);  // 4100 capped to 3600
            assertThat(on(1000, 3100).action()).isEqualTo(Action.STEP_UP);
        }

        @Test void holdsWhenAlreadyAtMax() {
            var d = on(2000, 3600);
            assertThat(d.action()).isEqualTo(Action.NONE);
            assertThat(d.reason()).contains("max");
        }

        @Test void partialStepToMaxAndOffLadderStepUp() {
            assertThat(on(1000, 3500).action()).isEqualTo(Action.STEP_UP);
            assertThat(on(1000, 3500).targetPowerW()).isEqualTo(3600);   // +100 to the cap
            assertThat(on(1000, 1200).targetPowerW()).isEqualTo(2200);   // off-ladder +1000
        }
    }

    @Nested
    class StepDown {
        @Test void stepsDownWhenMarginBelowLow() {
            var d = on(50, 1800);
            assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
            assertThat(d.targetPowerW()).isEqualTo(800);
        }

        @Test void stepsDownFromHighLadderTargets() {
            assertThat(on(0, 2800).targetPowerW()).isEqualTo(1800);
            assertThat(on(-200, 3600).targetPowerW()).isEqualTo(2600);
        }

        @Test void stepsDownJustBelowThreshold() {
            assertThat(on(99, 1800).action()).isEqualTo(Action.STEP_DOWN);
        }

        @Test void steppingDownToExactlyMinIsAllowed_notStop() {
            var d = on(50, 1800);            // 1800 - 1000 = 800 == min
            assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
            assertThat(d.targetPowerW()).isEqualTo(800);
        }

        @Test void reducesOffLadderTargetToFloorNeverBelowMin() {
            // 1200 W is a real value the miner reported — must drop to the floor, not stop.
            assertThat(on(50, 1200).action()).isEqualTo(Action.STEP_DOWN);
            assertThat(on(50, 1200).targetPowerW()).isEqualTo(800);
            assertThat(on(50, 1500).targetPowerW()).isEqualTo(800);
            assertThat(on(50, 850).targetPowerW()).isEqualTo(800);
            assertThat(on(50, 2200).targetPowerW()).isEqualTo(1200);   // 2200-1000, on-range
        }
    }

    @Nested
    class Deadzone {
        @Test void holdsAtLowerBoundary100() {
            var d = on(100, 1800);
            assertThat(d.action()).isEqualTo(Action.NONE);
            assertThat(d.reason()).contains("deadzone");
        }

        @Test void holdsThroughMidBand() {
            assertThat(on(500, 1800).action()).isEqualTo(Action.NONE);
            assertThat(on(999, 1800).action()).isEqualTo(Action.NONE);
        }
    }

    // ---------------------------------------------------------------- 3) STOP
    @Nested
    class StopMiner {
        @Test void stopsOnlyWhenAtFloorAndMarginLow() {
            var d = on(50, 800);             // already at the floor, can't reduce → stop
            assertThat(d.action()).isEqualTo(Action.STOP);
            assertThat(d.reason()).contains("floor");
        }

        @Test void aboveFloorReducesToFloorRatherThanStopping() {
            // Prefer running at the floor over shutting down — only stop from the floor.
            assertThat(on(50, 900).action()).isEqualTo(Action.STEP_DOWN);
            assertThat(on(50, 900).targetPowerW()).isEqualTo(800);
            assertThat(on(50, 1000).targetPowerW()).isEqualTo(800);
            assertThat(on(50, 1799).action()).isEqualTo(Action.STEP_DOWN);
            assertThat(on(50, 1799).targetPowerW()).isEqualTo(800);
        }

        @Test void stopsWithZeroOrNegativeMarginAtFloor() {
            assertThat(on(0, 800).action()).isEqualTo(Action.STOP);
            assertThat(on(-1000, 800).action()).isEqualTo(Action.STOP);
        }

        @Test void doesNotStopWhenMarginInDeadzoneEvenAtFloor() {
            assertThat(on(300, 800).action()).isEqualTo(Action.NONE);   // enough margin → hold
        }
    }

    // -------------------------------------------------- surplus invariant (never import)
    @Nested
    class SurplusInvariant {
        // The surplus a running miner can draw from = margin + its own current draw
        // (the meter counts the miner, so adding it back yields solar − base house).
        // A decision that leaves the miner running must set a power ≤ that surplus,
        // i.e. the miner must never pull from the grid.

        @Test void cloudDropBringsMinerUnderTheSurplusInOneDecision() {
            // Sunny: miner at 3000 W with +330 W to spare → inside the deadzone → hold.
            // (Holding at 3000 W is within the 3330 W surplus, so no import.)
            var hold = on(330, 3000);
            assertThat(hold.action()).isEqualTo(Action.NONE);
            assertThat(hold.targetPowerW()).isEqualTo(3000);                 // holds at the current draw
            assertThat(hold.targetPowerW()).isLessThanOrEqualTo(330 + 3000); // ≤ available surplus

            // Cloud arrives, solar collapses → margin swings to −1880 W (importing).
            // Surplus actually available = −1880 + 3000 = 1120 W. A single 1000 W step
            // (→2000 W) would STILL import; the planner must drop below the surplus now.
            int surplus = -1880 + 3000;                          // 1120 W
            var d = on(-1880, 3000);
            assertThat(d.action()).isEqualTo(Action.STEP_DOWN);
            assertThat(d.targetPowerW()).isLessThan(surplus);    // strictly under the surplus
            assertThat(d.targetPowerW()).isLessThan(3000);       // and it really reduced
            assertThat(d.targetPowerW()).isEqualTo(1020);        // surplus − low-margin buffer
        }

        @Test void stopsWhenEvenTheFloorWouldExceedTheSurplus() {
            assertThat(on(-3000, 3000).action()).isEqualTo(Action.STOP); // surplus 0  < 800 floor
            assertThat(on(-2500, 3000).action()).isEqualTo(Action.STOP); // surplus 500 < 800 floor
            // surplus 900 ≥ floor → run at the floor rather than stop.
            assertThat(on(-2100, 3000).action()).isEqualTo(Action.STEP_DOWN);
            assertThat(on(-2100, 3000).targetPowerW()).isEqualTo(800);
        }

        @Test void runningPowerNeverExceedsAvailableSurplus() {
            int[] margins = {-4000, -1880, -500, -100, 0, 50, 99, 100, 300, 999, 1000, 2500, 6000};
            int[] currents = {800, 1000, 1500, 2200, 3000, 3600};
            for (int margin : margins) {
                for (int cur : currents) {
                    var d = on(margin, cur);
                    Integer runningPower = switch (d.action()) {
                        case STEP_UP, STEP_DOWN, START -> d.targetPowerW();
                        case NONE -> cur;      // holding at the current draw
                        case STOP -> null;     // miner off — draws nothing
                    };
                    if (runningPower != null) {
                        assertThat(runningPower)
                                .as("margin=%d cur=%d action=%s", margin, cur, d.action())
                                .isLessThanOrEqualTo(margin + cur);   // ≤ available surplus
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- config stability
    @Test void detectsOscillationProneThresholds() {
        // deadzone (start-low) must be ≥ step, else a single step overshoots the band
        assertThat(MinerAutopilotPlanner.isStableConfig(1000, 100, 1000)).isFalse(); // 900 < 1000
        assertThat(MinerAutopilotPlanner.isStableConfig(1100, 100, 1000)).isTrue();  // 1000 == 1000
        assertThat(MinerAutopilotPlanner.isStableConfig(1000, 100, 900)).isTrue();   // 900 == 900
    }

    @Test void shippedDefaultsAreStable() {
        // Defaults start=1000, low=100, step=800 → deadzone 900 ≥ step 800 → stable.
        assertThat(MinerAutopilotPlanner.isStableConfig(1000, 100, 800)).isTrue();
    }

    // ---------------------------------------------------------------- guards
    @Test void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new MinerAutopilotPlanner(0, 3600, 1000, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);   // min <= 0
        assertThatThrownBy(() -> new MinerAutopilotPlanner(3600, 800, 1000, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);   // max < min
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 100, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);   // low >= start
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 1000, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);   // step <= 0
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 1000, -1, 800))
                .isInstanceOf(IllegalArgumentException.class);   // negative low margin
    }

    @Test void rejectsNegativeLowMarginThatWouldAntiBufferTheStepDown() {
        // A negative lowMarginW flips the step-down "fit" (floor(surplus) − lowMarginW) into
        // targeting ABOVE the surplus → import. Must be rejected at construction.
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 1000, -500, 800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowMarginW");
    }

    @Test void rejectsConfigsThatWouldImportFromGrid() {
        // start < min → starting at the floor would draw more than the surplus.
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 700, 100, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startMarginW");
        // step > start → a step-up fires at margin==start yet adds more draw than that.
        assertThatThrownBy(() -> new MinerAutopilotPlanner(800, 3600, 1000, 100, 1500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepW");
    }

    @Test void acceptsBoundaryConfigWhereStepEqualsStartAndStartEqualsMin() {
        // start == min and step == start are both the tightest safe boundary → allowed.
        assertThatCode(() -> new MinerAutopilotPlanner(800, 3600, 800, 100, 800))
                .doesNotThrowAnyException();
    }
}
