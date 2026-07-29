package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.autopilot.AutopilotStatus;
import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.autopilot.ConsumptionSourceHub;
import io.dmitrykislov.miner.autopilot.SolarSourceHub;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.port.PowerReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class TelemetryRecorderTest {

    private SolarSourceHub solar;
    private ConsumptionSourceHub consumption;
    private MinerStatusSource miner;
    private AutopilotStreamService autopilot;
    private TelemetryStore store;
    private TelemetryRecorder recorder;

    @BeforeEach
    void setup() {
        solar = new SolarSourceHub();
        consumption = new ConsumptionSourceHub();
        miner = mock(MinerStatusSource.class);
        autopilot = mock(AutopilotStreamService.class);
        store = mock(TelemetryStore.class);
        recorder = new TelemetryRecorder(new HistoryProperties(true, "data", 60_000, 31),
                store, solar, consumption, miner, autopilot);
    }

    private void emitSolar(double watts) { solar.publish(new PowerReading(Instant.now(), watts)); }
    private void emitConsumption(double watts) { consumption.publish(new PowerReading(Instant.now(), watts)); }

    private MinerStatus miner(boolean reachable, boolean running, String state, Integer powerTargetW, Integer drawW) {
        return new MinerStatus(reachable, running, state, null, "S19k", powerTargetW, true,
                running ? 1 : 0, 1, null, drawW, List.of(), running ? 600L : null, Instant.now(), null);
    }

    private TelemetrySample recordedSample() {
        ArgumentCaptor<TelemetrySample> cap = ArgumentCaptor.forClass(TelemetrySample.class);
        verify(store).recordSample(cap.capture());
        return cap.getValue();
    }

    @Test void capturesSolarConsumptionAndMinerFromTheSourcePorts() {
        emitSolar(3500);
        emitConsumption(1800);
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

    @Test void absentSolarConsumptionAndUnreachableMinerYieldNulls() {
        // Neither source has a live reading (inverter offline / meter stale → cleared).
        when(miner.latest()).thenReturn(miner(false, false, MinerStatus.OFFLINE, null, null));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.solarW()).isNull();
        assertThat(s.consumptionW()).isNull();
        assertThat(s.minerPowerW()).isNull();
        assertThat(s.minerState()).isEqualTo("OFFLINE");
    }

    @Test void solarPresentButConsumptionAbsentGivesNullConsumption() {
        emitSolar(2000);                               // generating, but no consumption reading
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
        emitSolar(3000);
        emitConsumption(1000);
        when(miner.latest()).thenReturn(miner(true, false, MinerStatus.SUSPENDED, 2400, null));
        when(autopilot.latest()).thenReturn(null);

        recorder.record();

        TelemetrySample s = recordedSample();
        assertThat(s.minerState()).isEqualTo("SUSPENDED");
        assertThat(s.minerPowerW()).isNull(); // not MINING → no power charted
        assertThat(s.minerDrawW()).isNull();
    }

    @Test void recordsEachAutopilotChangeExactlyOnce() {
        emitSolar(3000);
        emitConsumption(1000);
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

    @Test void doesNotReRecordAChangeRestoredFromHistoryOnRestart() {
        // On restart the autopilot restores its last change from history (same timestamp). The
        // recorder must not write that already-persisted event again as a duplicate row.
        Instant t1 = Instant.parse("2026-07-27T12:00:00Z");
        when(store.latestEvent()).thenReturn(new PowerChangeEvent(t1, "START", null, 1200, "restart"));
        when(miner.latest()).thenReturn(null);
        when(autopilot.latest()).thenReturn(new AutopilotStatus(true, t1, "restart", t1,
                new AutopilotStatus.Change(t1, "START", null, 1200, "restart")));

        recorder.record();
        verify(store, never()).recordEvent(any());   // already persisted → not duplicated

        // A genuinely newer change is still recorded.
        Instant t2 = t1.plusSeconds(60);
        when(autopilot.latest()).thenReturn(new AutopilotStatus(true, t2, "up", t2,
                new AutopilotStatus.Change(t2, "STEP_UP", 1200, 2800, "up")));
        recorder.record();
        verify(store, times(1)).recordEvent(any());   // exactly the new one, once
    }

    @Test void disabledRecorderDoesNothing() {
        var disabled = new TelemetryRecorder(new HistoryProperties(false, "data", 60_000, 31),
                store, solar, consumption, miner, autopilot);
        disabled.record();
        verifyNoInteractions(store);
    }
}
