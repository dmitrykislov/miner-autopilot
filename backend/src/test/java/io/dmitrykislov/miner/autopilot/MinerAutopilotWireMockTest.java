package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.inverter.InverterPoller;
import io.dmitrykislov.miner.inverter.WiNetWebSocketClient;
import io.dmitrykislov.miner.simulator.MockInverter;
import io.dmitrykislov.miner.simulator.MockMiner;
import io.dmitrykislov.miner.simulator.MockSolarAnalytics;
import io.dmitrykislov.miner.solaranalytics.HouseConsumptionState;
import io.dmitrykislov.miner.solaranalytics.HousePower;
import io.dmitrykislov.miner.solaranalytics.SolarAnalyticsClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;

/**
 * End-to-end autopilot test that boots the <b>entire Spring Boot context</b> and drives the real
 * chain — solar (inverter) + house consumption (Solar Analytics) → {@code PowerBalance} →
 * {@link EnergySampler} → {@link EnergyAverages} → {@link AutopilotGovernor} → {@link MinerAutopilot}
 * — against simulated devices ({@link MockMiner}, {@link MockSolarAnalytics}, {@link MockInverter}).
 *
 * <p>Each test reads as: arrange solar / consumption / miner state, run one tick, assert the exact
 * miner API calls. Ladder config: floor 1200, ceil 3600, step 400, headroom 200, start-surplus 1600,
 * up-max-rungs 2, emergency-gap 800. The governor's decision logic is unit-tested in
 * {@link AutopilotGovernorTest}; here we assert the wiring end to end.
 *
 * <p>The energy engine is overridden with a zero-coverage instance (below) so a single sampled
 * snapshot yields a trusted average — otherwise a window would need 60 s of wall-clock data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// This context has live @Scheduled pollers (inverter/consumption/sampler/autopilot). Close it after
// the class instead of leaving it cached, so those background tasks can't perturb later tests.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MinerAutopilotWireMockTest {

    static final MockMiner miner = new MockMiner();
    static final MockSolarAnalytics solar = new MockSolarAnalytics();

    @TestConfiguration
    static class ZeroCoverageEnergy {
        /** Generous windows + zero coverage so one sampled snapshot is enough (no 60 s wait). */
        @Bean @Primary
        EnergyAverages testEnergyAverages() {
            return new EnergyAverages(Duration.ofMinutes(5), Duration.ofMinutes(5),
                    Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("house.miner.host", miner::host);
        r.add("house.miner.poll-interval-ms", () -> 3_600_000);
        r.add("house.solar-analytics.enabled", () -> true);
        r.add("house.solar-analytics.host", solar::baseUrl);
        r.add("house.solar-analytics.user", () -> "test@example.com");
        r.add("house.solar-analytics.password", () -> "pw");
        r.add("house.solar-analytics.site-id", () -> "12345");
        r.add("house.solar-analytics.poll-interval-ms", () -> 3_600_000);
        r.add("house.inverter.host", () -> "localhost"); // client is mocked; host just needs to exist
        r.add("house.inverter.poll-interval-ms", () -> 3_600_000);
        r.add("auth.enabled", () -> false);              // no HTTP here; keep the context quiet
        r.add("house.autopilot.enabled", () -> true);    // never fires on its own — we call tick()
        r.add("house.autopilot.interval-ms", () -> 3_600_000);
        // Ladder shape:
        r.add("house.autopilot.floor-w", () -> 1200);
        r.add("house.autopilot.step-w", () -> 400);
        r.add("house.autopilot.headroom-w", () -> 200);
        r.add("house.autopilot.start-surplus-w", () -> 1600);
        r.add("house.autopilot.up-max-rungs-per-cycle", () -> 2);
        r.add("house.autopilot.emergency-gap-w", () -> 800);
        // Timing gates off (1 ms): the wiring/surplus math is what's under test here, not the
        // dampening cadence (that is covered in AutopilotGovernorTest). Any real gap between ticks
        // exceeds 1 ms, so a change in a prior test can't suppress this one.
        r.add("house.autopilot.up-interval-ms", () -> 1);
        r.add("house.autopilot.down-interval-ms", () -> 1);
        r.add("house.autopilot.long-window-ms", () -> 1);   // governor "mined long enough" (uptime 600 s ≫ 1 ms)
        r.add("house.autopilot.short-window-ms", () -> 1);   // (only the overridden bean's windows are used)
        r.add("house.autopilot.fresh-within-ms", () -> 1);
        r.add("house.autopilot.short-coverage-ms", () -> 1);
        r.add("house.autopilot.long-coverage-ms", () -> 1);
    }

    @AfterAll
    static void stopSimulators() { miner.stop(); solar.stop(); }

    @MockitoBean WiNetWebSocketClient winet;
    MockInverter inverter;

    @Autowired MinerAutopilot autopilot;
    @Autowired InverterPoller inverterPoller;
    @Autowired SolarAnalyticsClient solarAnalyticsClient;
    @Autowired EnergySampler energySampler;
    @Autowired EnergyAverages energy;
    @Autowired MinerService minerService;
    @Autowired HouseConsumptionState consumption;

    @BeforeEach
    void arrange() {
        miner.reset();
        solar.reset();
        energy.clear(); // EnergyAverages is a shared singleton — isolate each test
        inverter = new MockInverter(winet);
        // Start each test with consumption "unknown" (unmetered) so a tick that gates off the API
        // leaves the surplus genuinely unavailable rather than reusing a stale value.
        consumption.update(new HousePower(0, 0, null, null, false, Instant.now()));
    }

    /**
     * Run one autopilot tick against the live chain. Order matters: the Solar Analytics poll is
     * gated on live solar, so the inverter must publish solar first; then consumption is fetched;
     * then the inverter re-publishes a snapshot folding in the fresh consumption; then the energy
     * sampler records it; then the autopilot decides and acts.
     */
    private void tick() {
        inverterPoller.poll();       // 1) publish solar (the gate input for Solar Analytics)
        solar.clearRequests();       // count only this tick's consumption API calls
        solarAnalyticsClient.poll(); // 2) fetch consumption iff solar > threshold
        inverterPoller.poll();       // 3) rebuild the snapshot with solar + fresh consumption
        minerService.refresh();      // 4) publish current miner status so the sampler sees its draw
        energySampler.sample();      // 5) feed the rolling windows (solar + consumption + miner draw)
        miner.clearRequests();       // count only the tick's miner calls
        autopilot.tick();            // 6) decide + act
    }

    // ---------------------------------------------------------------- start
    @Test void startsAtFloorWhenSurplusMeetsStart() {
        inverter.solar(2.0); solar.consumption(400); miner.stopped(1200); // surplus 1600 (== start)
        tick();
        miner.verifyPowerSetTo(1200); // starts at the floor
        miner.verifyStarted();
    }

    @Test void doesNotStartBelowStartSurplus() {
        inverter.solar(1.5); solar.consumption(200); miner.stopped(1200); // surplus 1300 < 1600
        tick();
        miner.verifyNoMutations();
    }

    // ---------------------------------------------------------------- step up
    @Test void rampsUpCappedToTwoRungsWhenMiningSurplus() {
        inverter.solar(4.0); solar.consumption(1200); miner.mining(1200); // surplus = 2800+1200 = 4000
        tick();
        miner.verifyPowerSetTo(2000); // 1200 + 2·400, not straight to 3600
        miner.verifyNoStartOrStop();
    }

    @Test void holdsAtMaxNoPowerChange() {
        inverter.solar(6.0); solar.consumption(500); miner.mining(3600); // already at the ceiling
        tick();
        miner.verifyNoPowerChange();
    }

    // ---------------------------------------------------------------- step down
    @Test void stepsDownWhenOverDrawingSurplus() {
        // cur 3600, surplus = (2000−3600)+3600 = 2000 → over-drawing by 1600 ≥ emergency gap.
        inverter.solar(2.0); solar.consumption(3600); miner.mining(3600);
        tick();
        miner.verifyPowerSetTo(1600); // rung ≤ surplus−headroom (1800)
        miner.verifyNoStartOrStop();
    }

    // ---------------------------------------------------------------- stop
    @Test void stopsWhenSurplusCannotHoldFloor() {
        // cur 1600, surplus = (1000−1600)+1600 = 1000 → surplus−headroom 800 < floor 1200 → stop.
        inverter.solar(1.0); solar.consumption(1600); miner.mining(1600);
        tick();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    // ---------------------------------------------------------------- deadband
    @Test void holdsWhenSurplusMatchesCurrentRung() {
        // cur 2000, surplus = (300)+2000 = 2300 → surplus−headroom 2100 → rung 2000 = current → hold.
        inverter.solar(2.3); solar.consumption(2000); miner.mining(2000);
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- solar gate: skip the API when solar is low
    @Test void lowSolarSkipsConsumptionFetchAndStopsMiner() {
        // Solar 0.5 kW ≤ 800 W gate: the consumption API must NOT be called; consumption goes
        // unavailable → surplus unknown → the running miner is stopped.
        inverter.solar(0.5); solar.consumption(400); miner.mining(1800);
        tick();
        solar.verifyNotFetched();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    @Test void solarAboveGateDoesFetchConsumption() {
        inverter.solar(2.0); solar.consumption(400); miner.stopped(1200); // surplus 1600 → start
        tick();
        solar.verifyFetched();
        miner.verifyStarted();
    }

    @Test void solarExactlyAtGateSkipsFetch() {
        // Boundary: the gate is `solar <= min` (800 W), so exactly 800 W still skips the API.
        inverter.solar(0.8); solar.consumption(400); miner.mining(1800);
        tick();
        solar.verifyNotFetched();
        miner.verifyStopped();
    }

    @Test void solarDipBelowGateClearsStaleConsumptionAndStops() {
        // Regression guard: establish a fresh METERED reading while solar is high, then drop solar
        // below the gate. Consumption must go UNAVAILABLE (not reuse the stale reading that omits
        // the miner's draw) → surplus unknown → stop.
        inverter.solar(3.0); solar.consumption(1000);
        inverterPoller.poll(); solarAnalyticsClient.poll(); inverterPoller.poll(); energySampler.sample();
        inverter.solar(0.5); miner.mining(1800);
        tick();
        solar.verifyNotFetched();     // gated off
        miner.verifyStopped();        // stale reading NOT reused → surplus unknown → safe stop
        miner.verifyNoPowerChange();  // did not step down on an optimistic stale surplus
    }

    @Test void starvedEnergyWindowsStopRunningMinerEvenWithLiveFeed() {
        // Live feed is valid (online + metered + fresh) but the energy sampler never ran, so the
        // rolling windows are empty → no trusted surplus → stop (a dead sampler must not look healthy).
        inverter.solar(3.0); solar.consumption(1000); miner.mining(2800);
        inverterPoller.poll(); solarAnalyticsClient.poll(); inverterPoller.poll();
        // deliberately skip energySampler.sample()
        miner.clearRequests();
        autopilot.tick();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    // ------------------------------------------- safety: feed unavailable → stop
    @Test void stopsRunningMinerWhenInverterOffline() {
        inverter.offline(); solar.consumption(1000); miner.mining(1800);
        tick();
        solar.verifyNotFetched();   // offline → solar unknown (0) → gate skips the fetch
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    @Test void leavesStoppedMinerAloneWhenInverterOffline() {
        inverter.offline(); solar.consumption(1000); miner.stopped(1200);
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- suspended: skip (draws ~0 W), never stop/ramp
    @Test void suspendedMinerIsSkippedWhenInverterOffline() {
        inverter.offline(); solar.consumption(1000); miner.suspended(1800);
        tick();
        miner.verifyNoMutations(); // suspended draws ~0 W → nothing to protect against → skip
    }

    @Test void suspendedWithSurplusDoesNotRamp() {
        inverter.solar(3.0); solar.consumption(500); miner.suspended(1800);
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- miner unreachable → (re)start on surplus
    @Test void unreachableMinerIsRestartedWhenSurplusReturns() {
        // A stopped Braiins miner reports its Status query as unavailable (→ unreachable), yet the
        // start command still works. With surplus present the autopilot must recover it.
        inverter.solar(3.0); solar.consumption(500); miner.unreachable(); // surplus 2500 ≥ start 1600
        tick();
        miner.verifyPowerSetTo(1200); // aims for the floor
        miner.verifyStarted();        // and starts despite the unreachable status query
    }

    @Test void unreachableMinerWithNoSurplusIsLeftAlone() {
        // Unreachable AND the inverter is offline (no surplus data) → nothing to start.
        inverter.offline(); solar.consumption(500); miner.unreachable();
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- multi-tick sequences (temporal behaviour)
    @Test void stepsDownThenHoldsWithoutASpuriousStop() {
        // Regression for the post-power-change spurious STOP: after the autopilot steps the miner
        // down, the NEXT tick's short window still holds the old (higher) draw. With the averaged-draw
        // surplus that no longer under-states the surplus, so the miner HOLDS instead of being killed.
        // Tick 1 — mining at 3600, true surplus ~2100 → emergency step-down.
        inverter.solar(2.1); solar.consumption(3600); miner.mining(3600); // surplus 2100−3600+3600
        tick();
        miner.verifyPowerSetTo(1600);   // stepped down to the rung the surplus holds
        miner.verifyNoStartOrStop();
        // Tick 2 — miner now at 1600; the window still carries the 3600-draw sample from tick 1.
        inverter.solar(2.1); solar.consumption(1600); miner.mining(1600);
        tick();
        miner.verifyNoMutations();      // MUST hold — the old margin+cur estimate would have STOPPED here
    }

    @Test void restartsAfterAStopWhenSurplusReturns() {
        // Tick 1 — a deep deficit stops the miner.
        inverter.solar(0.9); solar.consumption(3600); miner.mining(3600); // surplus 900 < floor+headroom
        tick();
        miner.verifyStopped();
        // Tick 2 — surplus is comfortably back and the miner reads stopped → it is restarted.
        inverter.solar(4.0); solar.consumption(500); miner.stopped(1200);
        tick();
        miner.verifyStarted();
        miner.verifyPowerSetTo(1200);
    }
}
