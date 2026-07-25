package io.dmitrykislov.miner.braiins;

import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/** Broadcasts {@link MinerStatus} updates to SSE clients and retains the last one. */
@Service
public class MinerStreamService extends LatestBroadcaster<MinerStatus> {
}
