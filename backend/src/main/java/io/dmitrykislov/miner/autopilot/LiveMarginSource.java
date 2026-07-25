package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.springframework.stereotype.Component;

import java.util.OptionalDouble;

/**
 * Live margin from the latest inverter snapshot: {@code netSurplusKw} is already
 * solar − house (measured when the Powersensor is live), converted to watts.
 * Empty when there is no online snapshot yet.
 */
@Component
public class LiveMarginSource implements MarginSource {

    private final InverterStreamService inverter;

    public LiveMarginSource(InverterStreamService inverter) {
        this.inverter = inverter;
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
        return OptionalDouble.of(snap.powerBalance().netSurplusKw() * 1000.0);
    }
}
