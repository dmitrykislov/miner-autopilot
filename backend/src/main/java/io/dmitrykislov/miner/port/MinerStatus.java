package io.dmitrykislov.miner.port;

import java.util.List;
import java.time.Instant;

/**
 * Status of the Braiins OS+ miner, streamed to the UI.
 *
 * @param reachable     whether the GraphQL API responded
 * @param running       true = BOSMiner service is up (uptime present); it may still
 *                      be SUSPENDED (see {@link #state})
 * @param state         high-level state: MINING (hashing), SUSPENDED (service up but
 *                      paused, e.g. dead pools), STOPPED (service down), OFFLINE
 * @param statusReason  human explanation when not mining (e.g. "No pool configured")
 * @param model         e.g. "Antminer S19k Pro"
 * @param powerTargetW  configured autotuning power target in watts
 * @param tunerEnabled  whether autotuning is enabled
 * @param activePools   number of pools currently alive/connected
 * @param totalPools    number of configured pools
 * @param hashrateThs   live hashrate in TH/s (only while mining; else null)
 * @param powerDrawW    live approximate power draw in watts (only while mining; else null)
 * @param fans          live cooling-fan readings (only while the service is up; else empty)
 * @param uptimeSeconds seconds since the service started (null when stopped)
 * @param timestamp     when this status was captured (server clock)
 * @param error         populated instead of data when the miner is unreachable
 */
public record MinerStatus(
        boolean reachable,
        boolean running,
        String state,
        String statusReason,
        String model,
        Integer powerTargetW,
        boolean tunerEnabled,
        int activePools,
        int totalPools,
        Double hashrateThs,
        Integer powerDrawW,
        List<Fan> fans,
        Long uptimeSeconds,
        Instant timestamp,
        String error) {

    public static final String MINING = "MINING";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String STOPPED = "STOPPED";
    public static final String OFFLINE = "OFFLINE";

    public static MinerStatus offline(Instant ts, String error) {
        return new MinerStatus(false, false, OFFLINE, error, null, null, false,
                0, 0, null, null, List.of(), null, ts, error);
    }
}
