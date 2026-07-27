package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.autopilot.AutopilotStatus;
import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class TelemetryRecorderTest {

    private InverterStreamService inverter;
    private MinerStreamService miner;
    private AutopilotStreamService autopilot;
    private TelemetryStore store;
    private TelemetryRecorder recorder;

    @BeforeEach
    void setup() {
        inverter = mock(InverterStreamService.class);
        miner = mock(MinerStreamService.class);
        autopilot = mock(AutopilotStreamService.class);
        store = mock(TelemetryStore.class);
        recorder = new TelemetryRecorder(new HistoryProperties(true, "data", 60_000, 31),
                store, inverter, miner, autopilot);
    }

    private InverterSnapshot snap(boolean online, boolean metered, double solarKw, double houseKw) {
        PowerBalance pb = metered ? PowerBalance.metered(solarKw, houseKw) : PowerBalance.unmetered(solarKw);
        return new InverterSnapshot(online, "SG10RS", "SN", online ? "Running" : "Offline",
                Instant.now(), Map.of(), pb, List.of(), List.of(), null);
    }

    private MinerStatus miner(boolean reachable, boolean running, String state, Integer powerTargetW, Integer drawW) {
        return new MinerStatus(reachable, running, state, null, "S19k", powerTargetW, true,
                running ? 1 : 0, 1, null, drawW, List.of(), running ? 600L : null, Instant.now(), null);
    }

    private TelemetrySample recordedSample() {
        ArgumentCaptor<TelemetrySample> cap = ArgumentCaptor.forClass(TelemetrySample.class);
        verify(store).recordSample(cap.capture());
        return cap.getValue();
    }

    @Test void capturesSolarConsumptionAndMinerFromLiveFeed() {
        when(inverter.latest()).thenReturn(snap(true, true, 3.5, 1.8));       // 3500 W solar, 1800 W house
        when(miner.latest()).thenReturn(miner(true, true, MinerStatus.MINING, 2400, 2350));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.solarW()).isCloseTo(3500, within(1e-6));
        assertThat(s.consumptionW()).isCloseTo(1800, within(1e-6));
        assertThat(s.minerPowerW()).isEqualTo(2400);
        assertThat(s.minerDrawW()).isEqualTo(2350);
        assertThat(s.minerState()).isEqualTo("MINING");
        verify(store).prune(any());
    }

    @Test void offlineInverterAndUnreachableMinerYieldNulls() {
        when(inverter.latest()).thenReturn(snap(false, false, 0, 0));
        when(miner.latest()).thenReturn(miner(false, false, MinerStatus.OFFLINE, null, null));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.solarW()).isNull();
        assertThat(s.consumptionW()).isNull();
        assertThat(s.minerPowerW()).isNull();
        assertThat(s.minerState()).isEqualTo("OFFLINE");
    }

    @Test void unmeteredSolarPresentButConsumptionNull() {
        when(inverter.latest()).thenReturn(snap(true, false, 2.0, 0)); // online, generating, but not metered
        when(miner.latest()).thenReturn(miner(true, false, MinerStatus.STOPPED, 2400, null));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.solarW()).isCloseTo(2000, within(1e-6));
        assertThat(s.consumptionW()).isNull();
        assertThat(s.minerPowerW()).isNull();          // not running → power target not charted
        assertThat(s.minerState()).isEqualTo("STOPPED");
    }

    @Test void suspendedMinerChartsNoPowerOrDraw() {
        // SUSPENDED = service up but ~0 W draw → the miner line must not plot its target.
        when(inverter.latest()).thenReturn(snap(true, true, 3.0, 1.0));
        when(miner.latest()).thenReturn(miner(true, false, MinerStatus.SUSPENDED, 2400, null));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.minerState()).isEqualTo("SUSPENDED");
        assertThat(s.minerPowerW()).isNull(); // not MINING → no power charted
        assertThat(s.minerDrawW()).isNull();
    }

    @Test void recordsEachAutopilotChangeExactlyOnce() {
        when(inverter.latest()).thenReturn(snap(true, true, 3.0, 1.0));
        when(miner.latest()).thenReturn(miner(true, true, MinerStatus.MINING, 2400, 2350));

        Instant t1 = Instant.now().minusSeconds(120);
        var change1 = new AutopilotStatus.Change(t1, "STEP_UP", 2000, 2400, "surplus rose");
        when(autopilot.latest()).thenReturn(new AutopilotStatus(true, t1, "up", t1, change1));

        recorder.record();
        recorder.record(); // same change → must NOT be recorded twice

        Instant t2 = Instant.now();
        var change2 = new AutopilotStatus.Change(t2, "STEP_DOWN", 2400, 2000, "surplus fell");
        when(autopilot.latest()).thenReturn(new AutopilotStatus(true, t2, "down", t2, change2));
        recorder.record();

        ArgumentCaptor<PowerChangeEvent> cap = ArgumentCaptor.forClass(PowerChangeEvent.class);
        verify(store, times(2)).recordEvent(cap.capture());
        assertThat(cap.getAllValues()).extracting(PowerChangeEvent::action)
                .containsExactly("STEP_UP", "STEP_DOWN");
    }

    @Test void disabledRecorderDoesNothing() {
        var disabled = new TelemetryRecorder(new HistoryProperties(false, "data", 60_000, 31),
                store, inverter, miner, autopilot);
        disabled.record();
        verifyNoInteractions(store);
    }
}
