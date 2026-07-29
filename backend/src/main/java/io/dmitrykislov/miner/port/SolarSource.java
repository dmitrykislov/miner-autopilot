package io.dmitrykislov.miner.port;

import java.util.Optional;

/**
 * Inbound port: a live source of <b>solar generation</b> (watts). An adapter pushes readings in via
 * {@link #publish} — however it obtains them (poll, WebSocket, a subscribed Flux, an HTTP push …);
 * the autopilot engine reads the most recent one via {@link #latest}. The engine depends only on
 * this interface, so the solar source is <b>pluggable</b>: supply another bean that feeds it (the
 * built-in one is the Sungrow SG10RS inverter) to run on a different inverter/source.
 *
 * <p>Emit a reading only when a genuine value is available (e.g. the inverter is online). When none
 * is emitted, {@link #latest} simply goes stale and the engine treats the surplus as unknown.
 */
public interface SolarSource {

    /** Push the newest solar-generation reading in (called by the source adapter). */
    void publish(PowerReading reading);

    /**
     * Signal that no live reading is currently available (e.g. the inverter went offline), so
     * {@link #latest} immediately reports empty rather than serving a stale value until it ages out.
     */
    void clear();

    /** The most recent reading, or empty if none is currently available. */
    Optional<PowerReading> latest();
}
