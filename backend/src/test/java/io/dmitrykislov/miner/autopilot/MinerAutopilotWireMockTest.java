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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

/**
 * End-to-end autopilot test that boots the <b>entire Spring Boot context</b> and drives
 * the real margin chain — solar (inverter) − house consumption (Solar Analytics) →
 * {@code PowerBalance} → {@link LiveMarginSource} → {@link MinerAutopilot} — against
 * simulated devices ({@link MockMiner}, {@link MockSolarAnalytics}, {@link MockInverter}).
 *
 * <p>Each test reads as: arrange solar / consumption / miner state, run one tick, assert
 * the exact miner API calls. Limits: min 800, max 3600, start 1000, low 100, step 1000.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MinerAutopilotWireMockTest {

    static final MockMiner miner = new MockMiner();
    static final MockSolarAnalytics solar = new MockSolarAnalytics();

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
        r.add("house.autopilot.enabled", () -> true);    // never fires on its own — we call tick()
        r.add("house.autopilot.interval-ms", () -> 3_600_000);
        r.add("house.autopilot.start-margin-w", () -> 1000);
        r.add("house.autopilot.low-margin-w", () -> 100);
        r.add("house.autopilot.step-w", () -> 1000);
    }

    @AfterAll
    static void stopSimulators() { miner.stop(); solar.stop(); }

    @MockitoBean WiNetWebSocketClient winet;
    MockInverter inverter;

    @Autowired MinerAutopilot autopilot;
    @Autowired InverterPoller inverterPoller;
    @Autowired SolarAnalyticsClient solarAnalyticsClient;
    @Autowired MinerService minerService;
    @Autowired HouseConsumptionState consumption;

    @BeforeEach
    void arrange() {
        miner.reset();
        solar.reset();
        inverter = new MockInverter(winet);
        // Start each test with consumption "unknown" (unmetered) so a tick that gates off
        // the API leaves the margin genuinely unavailable rather than reusing a stale value.
        consumption.update(new HousePower(0, 0, null, null, false, Instant.now()));
    }

    /**
     * Run one autopilot tick against the live chain. Order matters: the Solar Analytics
     * poll is gated on live solar, so the inverter must publish solar first; then the
     * consumption poll runs; then the inverter re-publishes a snapshot that now folds in
     * the fresh consumption, which the autopilot reads.
     */
    private void tick() {
        inverterPoller.poll();       // 1) publish solar (the gate input for Solar Analytics)
        solar.clearRequests();       // count only this tick's consumption API calls
        solarAnalyticsClient.poll(); // 2) fetch consumption iff solar > threshold
        inverterPoller.poll();       // 3) rebuild the snapshot with solar + fresh consumption
        miner.clearRequests();       // count only the tick's miner calls
        autopilot.tick();            // 4) decide + act
    }

    // ---------------------------------------------------------------- start
    @Test void startsAtMinWhenOffAndMarginAtThreshold() {
        inverter.solar(2.0); solar.consumption(1000); miner.stopped(800); // margin = 1000 W (== start)
        tick();
        miner.verifyPowerSetTo(800); // starts at the floor
        miner.verifyStarted();
    }

    @Test void doesNotStartBelowStartMargin() {
        inverter.solar(1.2); solar.consumption(500); miner.stopped(800); // margin = 700 W < 1000
        tick();
        miner.verifyNoMutations();
    }

    // ---------------------------------------------------------------- step up
    @Test void stepsUpWhenMiningAndSurplus() {
        inverter.solar(3.0); solar.consumption(1500); miner.mining(800); // margin = 1500 W
        tick();
        miner.verifyPowerSetTo(1800); // 800 + step 1000
        miner.verifyNoStartOrStop();
    }

    @Test void stepsUpFromOffLadderTarget() {
        inverter.solar(3.0); solar.consumption(1500); miner.mining(1200); // margin 1500, target off the ladder
        tick();
        miner.verifyPowerSetTo(2200); // 1200 + 1000
    }

    @Test void stepUpCapsAtMaxPower() {
        inverter.solar(5.0); solar.consumption(500); miner.mining(3100); // big margin
        tick();
        miner.verifyPowerSetTo(3600); // 3100 + 1000 capped at max
    }

    @Test void holdsAtMaxNoPowerChange() {
        inverter.solar(6.0); solar.consumption(500); miner.mining(3600); // already at max
        tick();
        miner.verifyNoPowerChange();
    }

    // ---------------------------------------------------------------- step down
    @Test void stepsDownWhenMarginLow() {
        inverter.solar(1.0); solar.consumption(950); miner.mining(1800); // margin = 50 W < low
        tick();
        miner.verifyPowerSetTo(800); // drops to the floor, stays running
        miner.verifyNoStartOrStop();
    }

    @Test void stepsDownOffLadderTargetToFloor() {
        inverter.solar(1.0); solar.consumption(950); miner.mining(1500); // margin 50, off-ladder target
        tick();
        miner.verifyPowerSetTo(800);
    }

    // ---------------------------------------------------------------- stop
    @Test void stopsAtFloorWhenMarginLow() {
        inverter.solar(1.0); solar.consumption(950); miner.mining(800); // margin 50, already at floor
        tick();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    @Test void stopsWhenDeepDeficitEvenAboveFloor() {
        // solar 0.9 kW (above the 800 W gate, so consumption IS fetched), house 3.6 kW →
        // margin −2700 W; even the 800 W floor exceeds the available surplus (300 W) →
        // stop rather than step down.
        inverter.solar(0.9); solar.consumption(3600); miner.mining(3000);
        tick();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    // ------------------------------------------- solar gate: skip the API when solar is low
    @Test void lowSolarSkipsConsumptionFetchAndStopsMiner() {
        // Solar 0.5 kW ≤ 800 W gate: no usable surplus is possible, so the consumption API
        // must NOT be called; the margin goes unavailable → the running miner is stopped.
        inverter.solar(0.5); solar.consumption(400); miner.mining(1800);
        tick();
        solar.verifyNotFetched();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    @Test void solarAboveGateDoesFetchConsumption() {
        // Solar 2.0 kW > 800 W gate: the consumption API IS queried and the margin is used.
        inverter.solar(2.0); solar.consumption(1000); miner.stopped(800);
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
        // Regression guard for the import window: establish a fresh METERED reading while solar
        // is high, then drop solar below the gate. The gate must mark consumption UNAVAILABLE
        // (not reuse the now-stale reading that omits the miner's draw) → margin unknown → stop.
        inverter.solar(3.0); solar.consumption(1000);
        inverterPoller.poll(); solarAnalyticsClient.poll(); inverterPoller.poll(); // consumption now metered
        inverter.solar(0.5); miner.mining(1800);
        tick();
        solar.verifyNotFetched();     // gated off
        miner.verifyStopped();        // stale reading NOT reused → margin unknown → safe stop
        miner.verifyNoPowerChange();  // did not step down on an optimistic stale margin
    }

    // ---------------------------------------------------------------- deadzone
    @Test void holdsInDeadzone() {
        inverter.solar(2.3); solar.consumption(1800); miner.mining(1800); // margin = 500 W → deadzone
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- safety: margin unavailable → stop
    @Test void stopsRunningMinerWhenInverterOffline() {
        inverter.offline(); solar.consumption(1000); miner.mining(1800);
        tick();
        solar.verifyNotFetched();   // offline → solar unknown (0) → gate also skips the fetch
        miner.verifyStopped();
        miner.verifyNoPowerChange();
    }

    @Test void leavesStoppedMinerAloneWhenInverterOffline() {
        inverter.offline(); solar.consumption(1000); miner.stopped(800);
        tick();
        miner.verifyNoMutations();
    }

    @Test void stopsSuspendedMinerWhenInverterOffline() {
        // suspended still counts as "running" (service up) → stopped for safety.
        inverter.offline(); solar.consumption(1000); miner.suspended(1800);
        tick();
        miner.verifyStopped();
    }

    // ------------------------------------------- suspended (known margin): never ramp
    @Test void suspendedWithSurplusDoesNotRamp() {
        inverter.solar(3.0); solar.consumption(500); miner.suspended(1800); // margin 2500 W, but suspended
        tick();
        miner.verifyNoMutations();
    }

    // ------------------------------------------- miner unreachable → skip
    @Test void unreachableMinerSkips() {
        inverter.solar(3.0); solar.consumption(500); miner.unreachable();
        tick();
        miner.verifyNoMutations();
    }
}
