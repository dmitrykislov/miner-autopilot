package io.dmitrykislov.miner.port;

import java.util.Optional;

/**
 * Inbound port: a live source of <b>whole-home consumption</b> (watts). An adapter pushes readings
 * in via {@link #publish} — however it obtains them (poll, WebSocket, a subscribed Flux, an HTTP
 * push …); the autopilot engine reads the most recent one via {@link #latest}. The engine depends
 * only on this interface, so the consumption source is <b>pluggable</b> (the built-in one is Solar
 * Analytics' cloud meter).
 *
 * <p>Emit a reading only when a genuine, live measurement is available. When none is emitted,
 * {@link #latest} goes stale and the engine treats the surplus as unknown (and safely stops the
 * miner) rather than guessing.
 */
public interface ConsumptionSource {

    /** Push the newest whole-home consumption reading in (called by the source adapter). */
    void publish(PowerReading reading);

    /**
     * Signal that no live reading is currently available (meter stale, or gated off at low solar),
     * so {@link #latest} immediately reports empty rather than serving a stale value. This is how a
     * consumption outage promptly makes the surplus "unknown" (→ the autopilot safely stops).
     */
    void clear();

    /** The most recent reading, or empty if none is currently available. */
    Optional<PowerReading> latest();
}
