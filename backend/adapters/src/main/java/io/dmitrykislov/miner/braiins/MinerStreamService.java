package io.dmitrykislov.miner.braiins;

import io.dmitrykislov.miner.port.MinerStatus;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/**
 * Broadcasts {@link MinerStatus} updates to SSE clients and retains the last one — the built-in
 * {@link MinerStatusSource} (publish/latest/stream are inherited from {@link LatestBroadcaster}).
 */
@Service
public class MinerStreamService extends LatestBroadcaster<MinerStatus> implements MinerStatusSource {
}
