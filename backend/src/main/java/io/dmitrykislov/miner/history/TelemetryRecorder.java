package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.autopilot.AutopilotStatus;
import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Every {@code house.history.record-interval-ms} appends one {@link TelemetrySample} to the
 * {@link TelemetryStore} from the latest readings on the solar/consumption/miner-status ports (no
 * extra device I/O), records any new autopilot power-change as a {@link PowerChangeEvent}, and
 * prunes anything past the retention window.
 */
@Component
public class TelemetryRecorder {

    private final HistoryProperties cfg;
    private final TelemetryStore store;
    private final SolarSource solarSource;
    private final ConsumptionSource consumptionSource;
    private final MinerStatusSource miner;
    private final AutopilotStreamService autopilot;

    private volatile Instant lastEventAt; // dedup: the timestamp of the last event we wrote
    private volatile boolean seeded;      // false until lastEventAt is seeded from persisted history

    public TelemetryRecorder(HistoryProperties cfg, TelemetryStore store, SolarSource solarSource,
                             ConsumptionSource consumptionSource, MinerStatusSource miner,
                             AutopilotStreamService autopilot) {
        this.cfg = cfg;
        this.store = store;
        this.solarSource = solarSource;
        this.consumptionSource = consumptionSource;
        this.miner = miner;
        this.autopilot = autopilot;
    }

    @Scheduled(fixedDelayString = "${house.history.record-interval-ms:60000}",
               initialDelayString = "${house.history.record-interval-ms:60000}")
    public void record() {
        if (!cfg.enabled()) return;
        if (!seeded) {
            // Seed the dedup marker from the newest persisted event so a change the autopilot
            // RESTORED from history on restart (identical timestamp) is not written again as a
            // duplicate row. A genuinely newer change still records (its timestamp is later).
            PowerChangeEvent last = store.latestEvent();
            if (last != null) lastEventAt = last.at();
            seeded = true;
        }
        Instant now = Instant.now();
        store.recordSample(sample(now));
        captureNewEvent();
        store.prune(now);
    }

    private TelemetrySample sample(Instant now) {
        // Read the same source ports the engine uses → the chart is source-agnostic. A source with no
        // live reading (inverter offline / meter stale) reports empty → null → a gap in the chart.
        Double solarW = solarSource.latest().map(PowerReading::watts).orElse(null);
        Double consumptionW = consumptionSource.latest().map(PowerReading::watts).orElse(null);
        Integer minerPowerW = null, minerDrawW = null;
        String minerState = null;
        MinerStatus m = miner.latest();
        if (m != null) {
            minerState = m.reachable() ? m.state() : MinerStatus.OFFLINE;
            // Chart the miner's power only while it is actually MINING. A SUSPENDED miner is
            // "running" (service up) but draws ~0 W, so plotting its target would overstate
            // consumption and not line up with the (miner-inclusive) Home line.
            boolean mining = MinerStatus.MINING.equals(m.state());
            minerPowerW = (mining && m.powerTargetW() != null) ? m.powerTargetW() : null;
            minerDrawW = mining ? m.powerDrawW() : null;
        }
        return new TelemetrySample(now, solarW, consumptionW, minerPowerW, minerDrawW, minerState);
    }

    private void captureNewEvent() {
        AutopilotStatus ap = autopilot.latest();
        if (ap == null || ap.lastChange() == null) return;
        AutopilotStatus.Change c = ap.lastChange();
        if (c.at() == null) return;
        if (lastEventAt == null || c.at().isAfter(lastEventAt)) {
            store.recordEvent(new PowerChangeEvent(c.at(), c.action(), c.fromPowerW(), c.toPowerW(), c.detail()));
            lastEventAt = c.at();
        }
    }
}
