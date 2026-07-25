package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/**
 * Fans the latest {@link InverterSnapshot} out to all connected SSE clients and
 * retains the most recent one so new subscribers get an immediate value.
 */
@Service
public class InverterStreamService extends LatestBroadcaster<InverterSnapshot> {
}
