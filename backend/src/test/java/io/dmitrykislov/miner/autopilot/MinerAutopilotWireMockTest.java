package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.inverter.InverterPoller;
import io.dmitrykislov.miner.inverter.WiNetWebSocketClient;
import io.dmitrykislov.miner.simulator.MockInverter;
import io.dmitrykislov.miner.simulator.MockMiner;
import io.dmitrykislov.miner.simulator.MockSolarAnalytics;
import io.dmitrykislov.miner.solaranalytics.SolarAnalyticsClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    @BeforeEach
    void arrange() {
        miner.reset();
        solar.reset();
        inverter = new MockInverter(winet);
    }

    /** Pull fresh solar + consumption into the live snapshot, then run one autopilot tick. */
    private void tick() {
        solarAnalyticsClient.poll(); // → house consumption
        inverterPoller.poll();       // → solar + house snapshot
        miner.clearRequests();       // count only the tick's calls
        autopilot.tick();
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
        // solar 0.2 kW, house 3.6 kW → margin −3400 W; even the 800 W floor exceeds the
        // available surplus (−400 W) → stop rather than step down.
        inverter.solar(0.2); solar.consumption(3600); miner.mining(3000);
        tick();
        miner.verifyStopped();
        miner.verifyNoPowerChange();
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
