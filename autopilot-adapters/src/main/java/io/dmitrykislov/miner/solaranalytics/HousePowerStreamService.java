package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/**
 * Fans each new whole-home consumption reading out to all connected SSE clients
 * the instant it arrives, and retains the last one so new subscribers get a value.
 */
@Service
public class HousePowerStreamService extends LatestBroadcaster<HousePower> {
}
