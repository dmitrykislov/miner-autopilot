package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

import static org.mockito.Mockito.*;

/** Verifies the autopilot applies the planner's decision through {@link MinerService}. */
class MinerAutopilotTest {

    private MarginSource margin;
    private MinerService miner;
    private MinerStreamService stream;

    @BeforeEach
    void setup() {
        margin = mock(MarginSource.class);
        miner = mock(MinerService.class);
        stream = mock(MinerStreamService.class);
    }

    private HouseProperties props(boolean autopilotEnabled) {
        return new HouseProperties(null, null, null,
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),      // min 800 / max 3600
                new HouseProperties.Autopilot(autopilotEnabled, 30000, 1000, 100, 1000));
    }

    private MinerAutopilot autopilot(boolean enabled) {
        return new MinerAutopilot(margin, miner, stream, props(enabled));
    }

    private MinerStatus status(boolean reachable, boolean running, Integer powerTargetW) {
        return new MinerStatus(reachable, running, running ? "MINING" : "STOPPED", null, "S19k",
                powerTargetW, true, running ? 1 : 0, 1, null, null, List.of(),
                running ? 600L : null, Instant.now(), null);
    }

    /** Service up but paused (e.g. dead pools): running=true, state=SUSPENDED, ~0 W draw. */
    private MinerStatus suspended(Integer powerTargetW) {
        return new MinerStatus(true, true, MinerStatus.SUSPENDED, "dead pools", "S19k",
                powerTargetW, true, 0, 1, null, null, List.of(), 600L, Instant.now(), null);
    }

    // ---- guards ----
    @Test void disabledDoesNothing() {
        when(stream.latest()).thenReturn(status(true, false, 800));
        autopilot(false).tick();
        verifyNoInteractions(miner);
    }

    @Test void unreachableMinerDoesNothing() {
        when(stream.latest()).thenReturn(status(false, false, null));
        autopilot(true).tick();
        verifyNoInteractions(miner);
    }

    @Test void nullMinerStatusDoesNothing() {
        when(stream.latest()).thenReturn(null);
        autopilot(true).tick();
        verifyNoInteractions(miner);
    }

    // ---- safety: margin unavailable (solar OR house meter offline) → stop ----
    @Test void unknownMarginStopsRunningMiner() {
        when(stream.latest()).thenReturn(status(true, true, 1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void unknownMarginStopsSuspendedMiner() {
        // suspended still counts as "running" (service up) → stop for safety
        when(stream.latest()).thenReturn(suspended(1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        verify(miner).stop();
    }

    @Test void unknownMarginLeavesStoppedMinerAlone() {
        when(stream.latest()).thenReturn(status(true, false, null));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.empty());
        autopilot(true).tick();
        verifyNoInteractions(miner);
    }

    // ---- suspended: never ramp on phantom surplus ----
    @Test void suspendedWithSurplusDoesNotRamp() {
        // A suspended miner draws ~0 W, so the surplus is NOT headroom to step into.
        when(stream.latest()).thenReturn(suspended(1800));
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        autopilot(true).tick();
        verifyNoInteractions(miner);
    }

    // ---- 1) start ----
    @Test void startsMinerAtMinWhenOffAndMarginHigh() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        when(stream.latest()).thenReturn(status(true, false, 800));
        autopilot(true).tick();
        verifyStartLadderInOrder();
    }
    private void verifyStartLadderInOrder() {
        var io = inOrder(miner);
        io.verify(miner).setPowerTarget(800, true);   // start at the min
        io.verify(miner).start();
        verify(miner, never()).stop();
    }

    // ---- 2) update power up / down ----
    @Test void stepsUpWhenRunningAndSurplus() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(1500));
        when(stream.latest()).thenReturn(status(true, true, 800));
        autopilot(true).tick();
        verify(miner).setPowerTarget(1800, true);
        verify(miner, never()).start();
        verify(miner, never()).stop();
    }

    @Test void stepsDownWhenRunningAndMarginLow() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        when(stream.latest()).thenReturn(status(true, true, 1800));
        autopilot(true).tick();
        verify(miner).setPowerTarget(800, true);
        verify(miner, never()).stop();
    }

    // ---- 3) stop ----
    @Test void stopsWhenAtFloorAndMarginLow() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        when(stream.latest()).thenReturn(status(true, true, 800));
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void stepsDownOffLadderTargetToFloor() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        when(stream.latest()).thenReturn(status(true, true, 1200));   // off-ladder target
        autopilot(true).tick();
        verify(miner).setPowerTarget(800, true);                       // → floor, not stop
        verify(miner, never()).stop();
    }

    @Test void nullReportedPowerIsTreatedAsMin() {
        // running but the miner didn't report a target → treat as the min floor
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(50));
        when(stream.latest()).thenReturn(status(true, true, null));
        autopilot(true).tick();
        verify(miner).stop();                                          // at min + low margin → stop
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---- deadzone ----
    @Test void holdsInDeadzone() {
        when(margin.currentMarginWatts()).thenReturn(OptionalDouble.of(500));
        when(stream.latest()).thenReturn(status(true, true, 1800));
        autopilot(true).tick();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
        verify(miner, never()).stop();
    }
}
