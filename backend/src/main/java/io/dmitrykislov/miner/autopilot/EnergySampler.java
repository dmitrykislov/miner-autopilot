package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
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
 * The miner's own draw is recorded alongside each consumption sample (0 when it isn't mining) so the
 * averaged surplus can subtract the miner out consistently even across a power change.
 */
@Component
public class EnergySampler {

    private final InverterStreamService inverter;
    private final MinerStreamService miner;
    private final EnergyAverages energy;

    // A stopped Braiins miner reports its API as unreachable, so we can only carry the last-known
    // draw through a SHORT blip — a sustained outage means it's genuinely stopped (drawing 0), and
    // carrying forever would over-state the surplus and restart the miner below its hysteresis.
    private static final int MAX_CARRY_CYCLES = 3; // ~a few poll intervals

    private volatile Instant lastSolarTs;
    private volatile Instant lastConsumptionTs;
    private volatile double lastKnownDrawW; // carried through a transient miner-status blip
    private volatile int unreachableStreak; // consecutive samples with no confident draw

    public EnergySampler(InverterStreamService inverter, MinerStreamService miner, EnergyAverages energy) {
        this.inverter = inverter;
        this.miner = miner;
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
            // Co-sample the miner's draw so surplus = avg(solar) − avg(consumption) + avg(draw)
            // stays exact across a power change. 0 unless it is actually mining (a suspended/off
            // miner draws ~0 and its target is not "consumed").
            energy.recordMinerDraw(ts, currentMinerDrawW());
            lastConsumptionTs = ts;
        }
    }

    /**
     * The miner's live draw in watts to attribute to this consumption sample. Mining with a reading
     * → that draw. Reachable but not mining (stopped/suspended) → genuinely ~0 W. Unreachable / no
     * status → <b>carry the last-known draw for up to {@link #MAX_CARRY_CYCLES}</b>, then decay to 0.
     *
     * <p>Rationale: a transient miner-API blip must not record 0 W while the rig is still drawing
     * (consumption still includes it) — that would under-state the surplus and could spuriously stop
     * a healthy miner once it recovers. But a <em>sustained</em> unreachability is how a stopped
     * miner presents, and then it is genuinely drawing 0; carrying the old draw indefinitely would
     * over-state the surplus and restart the miner well below its start hysteresis (→ import). So the
     * carry is time-bounded: cover the blip, then assume stopped.
     */
    private double currentMinerDrawW() {
        MinerStatus m = miner.latest();
        if (m != null && MinerStatus.MINING.equals(m.state()) && m.powerDrawW() != null) {
            lastKnownDrawW = m.powerDrawW();
            unreachableStreak = 0;
        } else if (m != null && m.reachable()) {
            lastKnownDrawW = 0.0;                 // reachable and not mining → not drawing
            unreachableStreak = 0;
        } else if (++unreachableStreak > MAX_CARRY_CYCLES) {
            lastKnownDrawW = 0.0;                 // sustained outage → treat as stopped (0 W)
        }
        // else: brief blip within the carry window → keep lastKnownDrawW (rig likely still mining)
        return lastKnownDrawW;
    }

    private static boolean isNew(Instant ts, Instant last) {
        return last == null || ts.isAfter(last);
    }
}
