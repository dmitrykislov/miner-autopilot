package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Feeds {@link EnergyAverages} from the pluggable {@link SolarSource} and {@link ConsumptionSource}
 * ports. Runs on its own schedule (the inverter poll cadence) so the rolling windows are sampled
 * finely, independent of the coarser autopilot tick.
 *
 * <p>Each feed is recorded at most once per source-reading timestamp — deduping on the reading's own
 * timestamp, not wall-clock — so a held (unchanged) reading is never re-recorded (which would
 * over-weight it in the count-mean {@link RollingWindow} computes). Because a source only publishes
 * a fresh reading while it has a genuine live value, this also gives the safety gate for free: when a
 * source stops publishing (inverter offline, or the meter goes stale/gated), its {@code latest()}
 * timestamp stops advancing → nothing new is recorded → that window ages out → the surplus becomes
 * unknown and the autopilot safely stops the miner.
 *
 * <p>The miner's own draw is co-sampled with each consumption reading (0 when it isn't mining) so the
 * averaged surplus can subtract the miner out consistently even across a power change.
 */
@Component
public class EnergySampler {

    private final SolarSource solarSource;
    private final ConsumptionSource consumptionSource;
    private final MinerStatusSource miner;
    private final EnergyAverages energy;

    // A stopped Braiins miner reports its API as unreachable, so we can only carry the last-known
    // draw through a SHORT blip — a sustained outage means it's genuinely stopped (drawing 0), and
    // carrying forever would over-state the surplus and restart the miner below its hysteresis.
    private static final int MAX_CARRY_CYCLES = 3; // ~a few poll intervals

    private volatile Instant lastSolarTs;
    private volatile Instant lastConsumptionTs;
    private volatile double lastKnownDrawW; // carried through a transient miner-status blip
    private volatile int unreachableStreak; // consecutive samples with no confident draw

    public EnergySampler(SolarSource solarSource, ConsumptionSource consumptionSource,
                         MinerStatusSource miner, EnergyAverages energy) {
        this.solarSource = solarSource;
        this.consumptionSource = consumptionSource;
        this.miner = miner;
        this.energy = energy;
    }

    // Same cadence and initial delay as the inverter poller (there's nothing new to sample faster
    // than the sources publish); keying the initial delay to the interval — rather than a fixed short
    // value — also keeps the sampler quiet during fast manually-driven tests.
    @Scheduled(fixedDelayString = "${house.inverter.poll-interval-ms:10000}",
               initialDelayString = "${house.inverter.poll-interval-ms:10000}")
    public void sample() {
        PowerReading solar = solarSource.latest().orElse(null);
        if (solar != null && isNew(solar.at(), lastSolarTs)) {
            energy.recordSolar(solar.at(), solar.watts());
            lastSolarTs = solar.at();
        }
        PowerReading cons = consumptionSource.latest().orElse(null);
        if (cons != null && isNew(cons.at(), lastConsumptionTs)) {
            energy.recordConsumption(cons.at(), cons.watts());
            // Co-sample the miner's draw so surplus = avg(solar) − avg(consumption) + avg(draw)
            // stays exact across a power change. 0 unless it is actually mining (a suspended/off
            // miner draws ~0 and its target is not "consumed").
            energy.recordMinerDraw(cons.at(), currentMinerDrawW());
            lastConsumptionTs = cons.at();
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
