package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/**
 * Fans each new {@link HousePower} reading out to all connected SSE clients the
 * instant it arrives (no polling), and retains the last one so new subscribers
 * get an immediate value.
 */
@Service
public class HousePowerStreamService extends LatestBroadcaster<HousePower> {
}
