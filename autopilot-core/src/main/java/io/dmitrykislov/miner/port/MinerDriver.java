package io.dmitrykislov.miner.port;


/**
 * Outbound port: how the autopilot engine (and the REST API) drive a miner — read its live
 * status and start / stop / set its power target. Callers depend only on this interface, so
 * the concrete miner is <b>pluggable</b>: the current implementation drives Braiins OS+
 * ({@code braiins.MinerService}); supplying a different {@code MinerDriver} bean would let the
 * app control other hardware without touching the engine or the controllers.
 *
 * <p>Each command returns the miner's status immediately afterwards (a fresh read), so a caller
 * sees the result of what it just asked for.
 *
 * <p>Note: {@link MinerStatus} still lives in the {@code braiins} package for now. It is really a
 * source-agnostic domain type and is expected to move to a domain/port package in a later phase;
 * the port references it there until then.
 */
public interface MinerDriver {

    /** Read the miner's current live status (never cached). */
    MinerStatus refresh();

    /** Start mining; returns the status immediately after. */
    MinerStatus start();

    /** Stop mining; returns the status immediately after. */
    MinerStatus stop();

    /**
     * Set the autotuning power target in watts (the implementation clamps to the hardware's
     * hard limits). {@code apply=false} previews the change; {@code apply=true} commits it.
     */
    MinerStatus setPowerTarget(int watts, boolean apply);
}
