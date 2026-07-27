package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Feeds {@link EnergyAverages} from the latest inverter snapshot, which already folds together
 * solar generation (inverter) and whole-home consumption (Solar Analytics). Runs on its own
 * schedule (the inverter poll cadence) so the rolling windows are sampled finely, independent of
 * the coarser autopilot tick.
 *
 * <p>Each feed is recorded at most once per snapshot timestamp — deduping on the snapshot's own
 * timestamp, not wall-clock — so a held (unchanged) snapshot is never re-recorded. Re-recording a
 * held value would over-weight it in the mean (the "step-hold" bias {@link RollingWindow} is
 * explicitly designed to avoid).
 *
 * <p>Consumption is recorded only when the snapshot is <b>metered</b> (a live Solar Analytics
 * reading). When it isn't (feed offline, or gated off at low solar), consumption simply stops being
 * recorded and its window goes stale — which the autopilot treats as an unknown surplus (safe stop).
 */
@Component
public class EnergySampler {

    private final InverterStreamService inverter;
    private final EnergyAverages energy;

    private volatile Instant lastSolarTs;
    private volatile Instant lastConsumptionTs;

    public EnergySampler(InverterStreamService inverter, EnergyAverages energy) {
        this.inverter = inverter;
        this.energy = energy;
    }

    // Same cadence and initial delay as the inverter poller (there's nothing to sample until it has
    // published a snapshot); keying the initial delay to the interval — rather than a fixed short
    // value — also keeps the sampler quiet during fast manually-driven tests.
    @Scheduled(fixedDelayString = "${house.inverter.poll-interval-ms:10000}",
               initialDelayString = "${house.inverter.poll-interval-ms:10000}")
    public void sample() {
        InverterSnapshot snap = inverter.latest();
        if (snap == null || snap.timestamp() == null) return;
        Instant ts = snap.timestamp();
        PowerBalance pb = snap.powerBalance();
        if (pb == null) return;

        if (snap.online() && isNew(ts, lastSolarTs)) {
            energy.recordSolar(ts, pb.solarPowerKw() * 1000.0);
            lastSolarTs = ts;
        }
        if (pb.consumptionMetered() && pb.houseConsumptionKw() != null && isNew(ts, lastConsumptionTs)) {
            energy.recordConsumption(ts, pb.houseConsumptionKw() * 1000.0);
            lastConsumptionTs = ts;
        }
    }

    private static boolean isNew(Instant ts, Instant last) {
        return last == null || ts.isAfter(last);
    }
}
