package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * Live power margin from the latest inverter snapshot: {@code netSurplusKw} is the
 * exportable surplus (solar − whole-home consumption), in kW, converted to watts.
 * Because Solar Analytics measures the whole home (the miner's own draw included),
 * this margin already reflects the miner while it is mining.
 *
 * <p>Returns empty — so the autopilot never pilots on a guess — unless the latest
 * snapshot is <b>all three</b> of:
 * <ul>
 *   <li><b>online</b> — the last inverter poll succeeded;</li>
 *   <li><b>metered</b> — consumption is a real Solar Analytics reading, not the
 *       assumed baseline (the reading itself self-expires after its stale window);</li>
 *   <li><b>fresh</b> — no older than {@link #maxAge}. A stalled or thread-starved
 *       inverter poller keeps handing back its last value rather than an offline
 *       snapshot, so without this guard {@code latest()} could return an arbitrarily
 *       old but still-"online" reading and the autopilot would pilot on stale surplus.</li>
 * </ul>
 * An empty margin is treated by the autopilot as a safety stop.
 */
@Component
public class LiveMarginSource implements MarginSource {

    private final InverterStreamService inverter;
    private final Duration maxAge;

    public LiveMarginSource(InverterStreamService inverter, HouseProperties props) {
        this.inverter = inverter;
        // Tolerate a few missed/slow polls, but treat a longer gap as "no longer known".
        // Normal snapshots are one poll-interval apart; 4× catches a genuine stall while
        // riding out transient GC/scheduling jitter.
        this.maxAge = Duration.ofMillis(Math.max(1L, props.inverter().pollIntervalMs()) * 4);
    }

    @Override
    public OptionalDouble currentMarginWatts() {
        InverterSnapshot snap = inverter.latest();
        if (snap == null || !snap.online() || snap.powerBalance() == null) {
            return OptionalDouble.empty();
        }
        // Only act on a REAL margin: if house consumption isn't metered, netSurplus
        // uses the assumed baseline — too unreliable to start/stop hardware on.
        if (!snap.powerBalance().consumptionMetered()) {
            return OptionalDouble.empty();
        }
        // Only act on a FRESH margin: a stale snapshot means the poller stalled and we
        // no longer know the true surplus.
        if (snap.timestamp() == null
                || Duration.between(snap.timestamp(), Instant.now()).compareTo(maxAge) > 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(snap.powerBalance().netSurplusKw() * 1000.0);
    }
}
