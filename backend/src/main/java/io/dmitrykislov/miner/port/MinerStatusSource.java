package io.dmitrykislov.miner.port;

import io.dmitrykislov.miner.braiins.MinerStatus;
import reactor.core.publisher.Flux;

/**
 * Inbound port: the miner's live <b>status</b> feed (state, power draw, hashrate, fans, pools…). The
 * driving adapter {@link #publish}es each fresh status; the autopilot engine reads {@link #latest}
 * (chiefly to attribute the miner's own draw back into the surplus) and the UI subscribes to
 * {@link #stream}.
 *
 * <p>This is the read/subscribe counterpart to {@link MinerDriver} (which is the control surface).
 * Together they make the miner fully pluggable: a custom {@code MinerDriver} adapter publishes its
 * {@link MinerStatus} here so the engine sees its draw and the dashboard shows its state — no engine
 * or controller change needed. The built-in implementation is {@code braiins.MinerStreamService}.
 */
public interface MinerStatusSource {

    /** Push the newest miner status in (called by the miner adapter after each poll/command). */
    void publish(MinerStatus status);

    /** The most recent status, or {@code null} if none has been published yet. */
    MinerStatus latest();

    /** Live stream of statuses for SSE subscribers (starts with the latest, then updates). */
    Flux<MinerStatus> stream();
}
