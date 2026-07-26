package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

import static org.mockito.Mockito.*;

/**
 * Unit tests for the autopilot orchestration: it reads a FRESH miner state via
 * {@link MinerService#refresh()} each tick, and re-verifies state immediately
 * before every mutating operation (start/step/stop). min=800, max=3600,
 * start=1000, low=100, step=1000.
 */
class MinerAutopilotTest {

    private MarginSource margin;
    private MinerService miner;

    @BeforeEach
    void setup() {
        margin = mock(MarginSource.class);
        miner = mock(MinerService.class);
    }

    private HouseProperties props(boolean autopilotEnabled) {
        return new HouseProperties(null, null,
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),      // min 800 / max 3600
                new HouseProperties.Autopilot(autopilotEnabled, 30000, 1000, 100, 1000));
    }

    private MinerAutopilot autopilot(boolean enabled) {
        return new MinerAutopilot(margin, miner, props(enabled));
    }

    private MinerStatus status(boolean reachable, boolean running, Integer powerTargetW) {
        return new MinerStatus(reachable, running, running ? "MINING" : "STOPPED", null, "S19k",
                powerTargetW, true, running ? 1 : 0, 1, null, null, List.of(),
                running ? 600L : null, Instant.now(), null);
    }

    /** Service up but paused (dead pools): running=true, state=SUSPENDED, ~0 W draw. */
    private MinerStatus suspended(Integer powerTargetW) {
        return new MinerStatus(true, true, MinerStatus.SUSPENDED, "dead pools", "S19k",
                powerTargetW, true, 0, 1, null, null, List.of(), 600L, Instant.now(), null);
    }

    private void neverActs() {
        verify(miner, never()).start();
        verify(miner, never()).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- guards
    @Test void disabledDoesNothing() {
        autopilot(false).tick();
        verifyNoInteractions(miner); // never even reads state
    }

    @Test void unreachableMinerSkips() {
        when(miner.refresh()).thenReturn(status(false, false, null));
        autopilot(true).tick();
        neverActs();
    }

    @Test void nullStatusSkips() {
        when(miner.refresh()).thenReturn(null);
        autopilot(true).tick();
        neverActs();
    }

    // ------------------------------------------- safety: margin unavailable → stop
    @Test void unknownMarginStopsRunningMiner() {
        when(miner.refresh()).thenReturn(status(true, true, 1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void unknownMarginStopsSuspendedMiner() {
        when(miner.refresh()).thenReturn(suspended(1800)); // suspended still "running"
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        verify(miner).stop();
    }

    @Test void unknownMarginLeavesStoppedMinerAlone() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        neverActs();
    }

    // ------------------------------------------- suspended: never ramp on phantom surplus
    @Test void suspendedWithSurplusDoesNotRamp() {
        when(miner.refresh()).thenReturn(suspended(1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        autopilot(true).tick();
        neverActs();
    }

    // ---------------------------------------------------------------- start
    @Test void startsAtMinWhenOffAndMarginAtThreshold() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1000)); // exactly the start threshold
        autopilot(true).tick();
        var io = inOrder(miner);
        io.verify(miner).setPowerTarget(800, true); // start at the floor
        io.verify(miner).start();
        verify(miner, never()).stop();
    }

    @Test void doesNotStartBelowStartMargin() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(999)); // just below 1000
        autopilot(true).tick();
        neverActs();
    }

    @Test void startIsSkippedIfMinerAlreadyRunningAtOpTime() {
        // decision made while OFF, but by the time we act it's already running → skip.
        when(miner.refresh()).thenReturn(status(true, false, null), status(true, true, 800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        autopilot(true).tick();
        verify(miner, never()).start();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- step up
    @Test void stepsUpWhenMiningAndSurplus() {
        when(miner.refresh()).thenReturn(status(true, true, 800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        autopilot(true).tick();
        verify(miner).setPowerTarget(1800, true); // 800 + step 1000
        verify(miner, never()).start();
        verify(miner, never()).stop();
    }

    @Test void stepUpSkippedIfMinerStoppedAtOpTime() {
        when(miner.refresh()).thenReturn(status(true, true, 800), status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        autopilot(true).tick();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- step down
    @Test void stepsDownWhenMarginLow() {
        when(miner.refresh()).thenReturn(status(true, true, 1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        autopilot(true).tick();
        verify(miner).setPowerTarget(800, true);
        verify(miner, never()).stop();
    }

    @Test void stepsDownOffLadderTargetToFloor() {
        when(miner.refresh()).thenReturn(status(true, true, 1200)); // off-ladder target
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        autopilot(true).tick();
        verify(miner).setPowerTarget(800, true);
        verify(miner, never()).stop();
    }

    // ---------------------------------------------------------------- stop
    @Test void stopsAtFloorWhenMarginLow() {
        when(miner.refresh()).thenReturn(status(true, true, 800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void stopSkippedIfMinerAlreadyOffAtOpTime() {
        when(miner.refresh()).thenReturn(status(true, true, 800), status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        autopilot(true).tick();
        verify(miner, never()).stop();
    }

    @Test void nullReportedPowerTreatedAsFloorThenStops() {
        // running but no reported target → treated as the floor → low margin → stop.
        when(miner.refresh()).thenReturn(status(true, true, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- deadzone
    @Test void holdsInDeadzone() {
        when(miner.refresh()).thenReturn(status(true, true, 1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(500));
        autopilot(true).tick();
        neverActs();
    }
}
