package io.dmitrykislov.miner.port;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port: <b>read access</b> to recorded telemetry history. The engine depends only on this
 * interface — never on the concrete store — so persistence is a pluggable adapter (the built-in one
 * is a tiny file-backed store; a custom deployment could back it with a database, etc.).
 *
 * <p>The engine uses this to survive restarts without a blind spot: it replays the last window of
 * {@link TelemetrySample}s to warm its rolling averages, and restores its last {@link
 * PowerChangeEvent} so cooldown/dampening carry across a reboot. The write side (recording samples
 * and events, pruning) belongs to the persistence adapter itself, not to this port.
 */
public interface TelemetryHistory {

    /** Samples with timestamp {@code ≥ from}, time-ascending. */
    List<TelemetrySample> samplesSince(Instant from);

    /** Events with timestamp {@code ≥ from}, time-ascending. */
    List<PowerChangeEvent> eventsSince(Instant from);

    /** Samples with {@code from ≤ at ≤ to}, time-ascending. */
    List<TelemetrySample> samplesBetween(Instant from, Instant to);

    /** Events with {@code from ≤ at ≤ to}, time-ascending. */
    List<PowerChangeEvent> eventsBetween(Instant from, Instant to);

    /** The most recently recorded power-change event (newest), or null if none. */
    PowerChangeEvent latestEvent();
}
