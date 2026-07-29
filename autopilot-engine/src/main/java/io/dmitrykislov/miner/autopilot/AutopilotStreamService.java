package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/** Broadcasts {@link AutopilotStatus} updates to SSE clients and retains the last one. */
@Service
public class AutopilotStreamService extends LatestBroadcaster<AutopilotStatus> {
}
