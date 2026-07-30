package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.MinerDriver;
import io.dmitrykislov.miner.port.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetryHistory;
import io.dmitrykislov.miner.port.PowerReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Orchestration tests for the autopilot: it reads a FRESH miner state via
 * {@link MinerDriver#refresh()} each tick, derives the {@link AutopilotGovernor} inputs from the
 * live feed (the SolarSource + ConsumptionSource ports for validity + {@link EnergyAverages} for the averaged
 * surplus), applies the decision, and re-verifies state immediately before every mutating op.
 *
 * <p>Config: floor 1200, ceil (miner max) 3600, step 400, headroom 200, start-surplus 1600,
 * up-max-rungs 2, emergency-gap 800. Windows are 3 min with ~0 coverage so a single fed pair of
 * samples yields a trusted average. The governor's own decision logic is exhaustively covered in
 * {@link AutopilotGovernorTest}; here we assert the <em>wiring</em>.
 */
class MinerAutopilotTest {

    private MinerDriver miner;
    private SolarSourceHub solarSource;
    private ConsumptionSourceHub consumptionSource;
    private EnergyAverages energy;
    private AutopilotStreamService stream;
    private TelemetryHistory history;

    @BeforeEach
    void setup() {
        miner = mock(MinerDriver.class);
        history = mock(TelemetryHistory.class); // no persisted history by default → latestEvent() == null
        solarSource = new SolarSourceHub();
        consumptionSource = new ConsumptionSourceHub();
        energy = new EnergyAverages(
                Duration.ofMinutes(3), Duration.ofMinutes(3), Duration.ofSeconds(90),
                Duration.ofMillis(1), Duration.ofMillis(1)); // tiny coverage → one sample pair suffices
    }

    private HouseProperties props(boolean autopilotEnabled) {
        return new HouseProperties(null, null,
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),      // min 800 / max 3600
                new HouseProperties.Autopilot(autopilotEnabled, 30_000,
                        1200,    // floorW
                        400,     // stepW
                        200,     // headroomW
                        1600,    // startSurplusW
                        2,       // upMaxRungsPerCycle
                        800,     // emergencyGapW
                        180_000, // upIntervalMs (== longWindow)
                        180_000, // downIntervalMs (≥ shortWindow)
                        180_000, // shortWindowMs
                        180_000, // longWindowMs
                        90_000,  // freshWithinMs
                        1,       // shortCoverageMs (tiny)
                        1,       // longCoverageMs (tiny)
                        -1));    // minRunMs disabled — these wiring tests predate the min-run guard
                                 // (the guard's logic is covered in AutopilotGovernorTest)
    }

    private MinerAutopilot autopilot(boolean enabled) {
        stream = new AutopilotStreamService();
        return new MinerAutopilot(energy, solarSource, consumptionSource, miner, props(enabled), stream, history);
    }

    /** Autopilot with explicit inverter + Solar-Analytics poll cadences (for the freshness-bound test). */
    private MinerAutopilot autopilotWithPolls(long inverterPollMs, long saPollMs) {
        stream = new AutopilotStreamService();
        var house = new HouseProperties(
                new HouseProperties.Inverter("h", 443, "/ws", "u", "p", inverterPollMs, 8000),
                new HouseProperties.SolarAnalytics(true, "http://x", "u", "p", "12345", saPollMs, 60, 8000, 800),
                new HouseProperties.Miner(true, "h", 0, 0, "", 0, 0),
                new HouseProperties.Autopilot(true, 30_000, 1200, 400, 200, 1600, 2, 800,
                        180_000, 180_000, 180_000, 180_000, 90_000, 1, 1, -1));
        return new MinerAutopilot(energy, solarSource, consumptionSource, miner, house, stream, history);
    }

    private MinerStatus status(boolean reachable, boolean running, Integer powerTargetW) {
        return status(reachable, running, powerTargetW, running ? 600L : null);
    }

    private MinerStatus status(boolean reachable, boolean running, Integer powerTargetW, Long uptimeS) {
        return new MinerStatus(reachable, running, running ? "MINING" : "STOPPED", null, "S19k",
                powerTargetW, true, running ? 1 : 0, 1, null, null, List.of(),
                uptimeS, Instant.now(), null);
    }

    @Test
    void restoresLastChangeFromHistoryOnStartup() {
        // A restart must not forget the last power change: the governor's cooldown /
        // up-dampening are measured from it. Seed it from the newest persisted event.
        Instant when = Instant.parse("2026-07-27T11:59:00Z");
        when(history.latestEvent()).thenReturn(
                new PowerChangeEvent(when, "STOP", 1200, null, "surplus 900W can't hold floor → stop"));

        var ap = autopilot(true).status();

        assertThat(ap.lastChange()).isNotNull();
        assertThat(ap.lastChange().action()).isEqualTo("STOP");
        assertThat(ap.lastChange().at()).isEqualTo(when);
        assertThat(ap.lastChangeAt()).isEqualTo(when);
    }

    @Test
    void toleratesEmptyHistoryOnStartup() {
        when(history.latestEvent()).thenReturn(null); // fresh install / history disabled
        assertThat(autopilot(true).status().lastChange()).isNull();
    }

    /** Service up but paused (dead pools): running=true, state=SUSPENDED, ~0 W draw. */
    private MinerStatus suspended(Integer powerTargetW) {
        return new MinerStatus(true, true, MinerStatus.SUSPENDED, "dead pools", "S19k",
                powerTargetW, true, 0, 1, null, null, List.of(), 600L, Instant.now(), null);
    }

    /**
     * Make the feed valid/invalid at {@code ts} by publishing to the source ports (values don't
     * matter here — the surplus comes from {@link #feedEnergy}; this only drives feedValid): a fresh
     * solar reading when "online", a fresh consumption reading when also "metered".
     */
    private void publishSnapshot(boolean online, boolean metered, Instant ts) {
        if (online) solarSource.publish(new PowerReading(ts, 2000));
        if (online && metered) consumptionSource.publish(new PowerReading(ts, 1000));
    }

    /**
     * Feed the energy engine two timestamped samples spanning the coverage → trusted averages.
     * Records solar/consumption/draw so the miner-independent surplus works out to
     * {@code availSurplusW}: surplus = solar − consumption + draw = availSurplus − draw + draw.
     */
    private void feedEnergy(double availSurplusW, int minerDrawW) {
        Instant now = Instant.now();
        for (Instant at : List.of(now.minusSeconds(20), now)) {
            energy.recordSolar(at, availSurplusW);
            energy.recordConsumption(at, minerDrawW);   // house = the miner draw (base 0)
            energy.recordMinerDraw(at, minerDrawW);     // add the draw back → surplus = availSurplus
        }
    }

    /**
     * A fully valid live feed: a fresh online+metered snapshot AND fresh, well-covered windows, so
     * the governor sees a trusted surplus {@code availSurplusW} for a miner drawing {@code minerDrawW}.
     */
    private void liveFeed(double availSurplusW, int minerDrawW) {
        publishSnapshot(true, true, Instant.now());
        feedEnergy(availSurplusW, minerDrawW);
    }

    private void neverActs() {
        verify(miner, never()).start();
        verify(miner, never()).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- guards
    @Test void disabledDoesNothing() {
        autopilot(false).tick();
        verifyNoInteractions(miner);
    }

    @Test void unreachableMinerWithNoFeedDoesNothing() {
        // Unreachable AND no surplus data → nothing to do (can't step/stop, no reason to start).
        when(miner.refresh()).thenReturn(status(false, false, null));
        autopilot(true).tick();
        neverActs();
    }

    @Test void unreachableMinerIsRestartedWhenSurplusReturns() {
        // The key recovery: a stopped Braiins miner reports unreachable; with surplus back, restart it.
        when(miner.refresh()).thenReturn(status(false, false, null)); // offline/unreachable
        liveFeed(2000, 0);                                            // surplus 2000 ≥ start 1600
        var ap = autopilot(true);
        var io = inOrder(miner);
        ap.tick();
        io.verify(miner).setPowerTarget(1200, true); // aim for the floor
        io.verify(miner).start();                    // and bring it up despite the unreachable status
        assertThat(ap.status().lastChange().action()).isEqualTo("START");
        assertThat(ap.status().lastDecision()).contains("restart");
    }

    @Test void nullStatusSkips() {
        when(miner.refresh()).thenReturn(null);
        autopilot(true).tick();
        neverActs();
    }

    // ------------------------------------------- safety: feed invalid/stale → stop
    @Test void staleFeedStopsRunningMiner() {
        when(miner.refresh()).thenReturn(status(true, true, 2000)); // no live feed published/fed
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void staleFeedLeavesOffMinerAlone() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        autopilot(true).tick();
        neverActs();
    }

    @Test void staleSnapshotStopsRunningMiner() {
        // Windows are fresh, but the live snapshot is older than the freshness bound (4× the slower
        // feed's poll interval) → the poller stalled → surplus unknown → stop.
        when(miner.refresh()).thenReturn(status(true, true, 2800));
        publishSnapshot(true, true, Instant.now().minusSeconds(120));
        feedEnergy(3000, 2800);
        autopilot(true).tick();
        verify(miner).stop();
    }

    @Test void consumptionFreshnessIsNotStarvedByAFasterInverterPoll() {
        // Footgun: the staleness bound must accommodate the SLOWER feed (Solar Analytics, 15s),
        // not just the inverter poll. With a fast inverter poll (3s) the old bound was 4×3s = 12s,
        // so a consumption reading only 14s old — perfectly fresh for its own source — was wrongly
        // judged stale and a healthy, holding miner was STOPPED. It must not be.
        var ap = autopilotWithPolls(3000, 15000);
        when(miner.refresh()).thenReturn(status(true, true, 2000));
        Instant recent = Instant.now().minusSeconds(14); // < SA cadence, > the old inverter-only 12s bound
        solarSource.publish(new PowerReading(recent, 2000));
        consumptionSource.publish(new PowerReading(recent, 1000));
        feedEnergy(2300, 2000); // surplus 2300, cur 2000 → holds at the current rung (no stop, no step)
        ap.tick();
        verify(miner, never()).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    @Test void unmeteredSnapshotStopsRunningMiner() {
        // Snapshot is online and fresh but consumption is not metered (Solar Analytics gated/stale)
        // → the surplus can't be computed → stop, even though the windows still hold old samples.
        when(miner.refresh()).thenReturn(status(true, true, 2800));
        publishSnapshot(true, false, Instant.now());
        feedEnergy(3000, 2800);
        autopilot(true).tick();
        verify(miner).stop();
    }

    @Test void deadSamplerStopsRunningMinerEvenWithValidSnapshot() {
        // Isolate the energy.dataFresh() arm of the gate: the live snapshot is fully valid
        // (feedValid == true), but the rolling windows were never fed (sampler dead) → stop.
        when(miner.refresh()).thenReturn(status(true, true, 2800));
        publishSnapshot(true, true, Instant.now()); // feedValid true
        // deliberately do NOT feedEnergy() → energy.dataFresh() false
        autopilot(true).tick();
        verify(miner).stop();
    }

    // ------------------------------------------- suspended: skip (draws ~0 W)
    @Test void suspendedWithSurplusDoesNotRamp() {
        when(miner.refresh()).thenReturn(suspended(1800));
        liveFeed(3000, 1800);
        autopilot(true).tick();
        neverActs();
    }

    @Test void suspendedWithStaleFeedIsSkippedNotStopped() {
        when(miner.refresh()).thenReturn(suspended(1800)); // no feed → still just skip (draws ~0 W)
        autopilot(true).tick();
        neverActs();
    }

    // ---------------------------------------------------------------- start
    @Test void startsAtFloorWhenSurplusAtStartThreshold() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        liveFeed(1600, 0); // exactly the start surplus
        autopilot(true).tick();
        var io = inOrder(miner);
        io.verify(miner).setPowerTarget(1200, true); // start at the floor
        io.verify(miner).start();
        verify(miner, never()).stop();
    }

    @Test void doesNotStartBelowStartSurplus() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        liveFeed(1599, 0);
        autopilot(true).tick();
        neverActs();
    }

    @Test void startIsSkippedIfMinerAlreadyRunningAtOpTime() {
        when(miner.refresh()).thenReturn(status(true, false, null), status(true, true, 1200));
        liveFeed(2000, 0);
        autopilot(true).tick();
        verify(miner, never()).start();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    // ---------------------------------------------------------------- step up
    @Test void rampsUpCappedToTwoRungsWhenMiningAndSurplus() {
        when(miner.refresh()).thenReturn(status(true, true, 1200));
        liveFeed(3800, 1200); // could reach 3600, but capped to 1200 + 2·400 = 2000
        autopilot(true).tick();
        verify(miner).setPowerTarget(2000, true);
        verify(miner, never()).start();
        verify(miner, never()).stop();
    }

    @Test void freshlyStartedMinerDoesNotRampUpYet() {
        // uptime 30 s < long window (3 min) → not mined long enough for a valid up-average.
        when(miner.refresh()).thenReturn(status(true, true, 1200, 30L));
        liveFeed(3800, 1200);
        autopilot(true).tick();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    @Test void miningWithNoReportedUptimeDoesNotRampOnFirstObservation() {
        // Null uptime exercises the trackMiningSince fallback: miningSince seeded to "now" → not
        // mined long enough → no ramp on the first observation, even with plenty of surplus.
        when(miner.refresh()).thenReturn(status(true, true, 1200, null));
        liveFeed(3800, 1200);
        autopilot(true).tick();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).stop();
    }

    @Test void stepUpSkippedIfMinerStoppedAtOpTime() {
        when(miner.refresh()).thenReturn(status(true, true, 1200), status(true, false, null));
        liveFeed(3800, 1200);
        autopilot(true).tick();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
    }

    @Test void doesNotRampImmediatelyAfterResumingFromSuspension() {
        // Guard against ramping on a surplus that was inflated while the miner was suspended (drawing
        // ~0 W): once we've OBSERVED a suspension, a resume must re-arm the "mined long enough" clock
        // rather than trusting the (unreset) service uptime.
        var ap = autopilot(true);
        when(miner.refresh()).thenReturn(suspended(2000));
        liveFeed(4000, 2000);
        ap.tick();                       // observe the suspension
        clearInvocations(miner);
        when(miner.refresh()).thenReturn(status(true, true, 2000, 600L)); // uptime says "long"
        liveFeed(4000, 2000);
        ap.tick();                       // resume — must NOT ramp yet
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    // ---------------------------------------------------------------- step down
    @Test void stepsDownWhenSurplusDrops() {
        when(miner.refresh()).thenReturn(status(true, true, 3600));
        liveFeed(1800, 3600); // over-drawing by 1800 ≥ emergency gap → down to rung 1600
        autopilot(true).tick();
        verify(miner).setPowerTarget(1600, true);
        verify(miner, never()).stop();
    }

    // ---------------------------------------------------------------- stop
    @Test void stopsWhenSurplusCannotHoldFloor() {
        when(miner.refresh()).thenReturn(status(true, true, 1600));
        liveFeed(1300, 1600); // S−headroom = 1100 < floor 1200 → stop
        autopilot(true).tick();
        verify(miner).stop();
        verify(miner, never()).setPowerTarget(anyInt(), anyBoolean());
        verify(miner, never()).start();
    }

    @Test void stopSkippedIfMinerAlreadyOffAtOpTime() {
        when(miner.refresh()).thenReturn(status(true, true, 1600), status(true, false, null));
        liveFeed(1300, 1600);
        autopilot(true).tick();
        verify(miner, never()).stop();
    }

    // ---------------------------------------------------------------- hold
    @Test void holdsWhenSurplusMatchesCurrentRung() {
        when(miner.refresh()).thenReturn(status(true, true, 2000));
        liveFeed(2300, 2000); // S−headroom = 2100 → rung 2000 = current → hold
        autopilot(true).tick();
        neverActs();
    }

    // ------------------------------------------- runtime enable/disable + status
    @Test void enabledFlagDefaultsFromConfig() {
        assertThat(autopilot(true).isEnabled()).isTrue();
        assertThat(autopilot(false).isEnabled()).isFalse();
    }

    @Test void disabledAtRuntimeDoesNothingEvenIfConfigEnabled() {
        var ap = autopilot(true);
        ap.setEnabled(false);
        when(miner.refresh()).thenReturn(status(true, false, null));
        liveFeed(5000, 0); // would normally start
        ap.tick();
        neverActs();
        assertThat(ap.isEnabled()).isFalse();
    }

    @Test void toggleUpdatesStatusAndPublishesToStream() {
        var ap = autopilot(false);
        assertThat(ap.status().enabled()).isFalse();
        ap.setEnabled(true);
        assertThat(ap.isEnabled()).isTrue();
        assertThat(ap.status().enabled()).isTrue();
        assertThat(stream.latest().enabled()).isTrue();
    }

    @Test void recordsStartChangeWithDetails() {
        when(miner.refresh()).thenReturn(status(true, false, null));
        liveFeed(2000, 0);
        var ap = autopilot(true);
        ap.tick();
        var s = ap.status();
        assertThat(s.evaluatedAt()).isNotNull();
        assertThat(s.lastDecision()).contains("start");
        assertThat(s.lastChangeAt()).isNotNull();
        assertThat(s.lastChange().action()).isEqualTo("START");
        assertThat(s.lastChange().fromPowerW()).isNull();
        assertThat(s.lastChange().toPowerW()).isEqualTo(1200);
    }

    @Test void recordsStepUpChangeFromTo() {
        when(miner.refresh()).thenReturn(status(true, true, 1200));
        liveFeed(3800, 1200);
        var ap = autopilot(true);
        ap.tick();
        assertThat(ap.status().lastChange().action()).isEqualTo("STEP_UP");
        assertThat(ap.status().lastChange().fromPowerW()).isEqualTo(1200);
        assertThat(ap.status().lastChange().toPowerW()).isEqualTo(2000);
    }

    @Test void recordsStopChangeOnStaleFeed() {
        when(miner.refresh()).thenReturn(status(true, true, 1600)); // no feed → stale → stop
        var ap = autopilot(true);
        ap.tick();
        assertThat(ap.status().lastChange().action()).isEqualTo("STOP");
        assertThat(ap.status().lastChange().fromPowerW()).isEqualTo(1600);
        assertThat(ap.status().lastChange().toPowerW()).isNull();
        assertThat(ap.status().lastDecision()).contains("no fresh");
    }

    @Test void holdRecordsDecisionButNoChange() {
        when(miner.refresh()).thenReturn(status(true, true, 2000));
        liveFeed(2300, 2000);
        var ap = autopilot(true);
        ap.tick();
        assertThat(ap.status().lastDecision()).contains("holding");
        assertThat(ap.status().lastChange()).isNull();
        assertThat(ap.status().lastChangeAt()).isNull();
    }
}
