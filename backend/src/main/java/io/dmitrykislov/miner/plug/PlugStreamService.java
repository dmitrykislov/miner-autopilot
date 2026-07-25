package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.stream.LatestBroadcaster;
import org.springframework.stereotype.Service;

/** Broadcasts {@link PlugStatus} updates to SSE clients and retains the last one. */
@Service
public class PlugStreamService extends LatestBroadcaster<PlugStatus> {
}
