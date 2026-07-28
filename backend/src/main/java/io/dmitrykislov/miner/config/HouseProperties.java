package io.dmitrykislov.miner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * All configuration for monitoring the house, bound from {@code house.*}.
 *
 * <pre>
 * house
 *  ├─ inverter        → the Sungrow SG10RS / WiNet-S (solar generation)
 *  ├─ solar-analytics → Solar Analytics cloud API (measured whole-home consumption)
 *  └─ miner           → the Braiins OS+ miner (Antminer S19k Pro) GraphQL API
 * </pre>
 */
@ConfigurationProperties(prefix = "house")
public record HouseProperties(
        @NestedConfigurationProperty Inverter inverter,
        @NestedConfigurationProperty SolarAnalytics solarAnalytics,
        @NestedConfigurationProperty Miner miner,
        @NestedConfigurationProperty Autopilot autopilot) {

    public HouseProperties {
        if (inverter == null) inverter = new Inverter(null, 0, null, null, null, 0, 0);
        if (solarAnalytics == null) solarAnalytics = new SolarAnalytics(true, null, null, null, null, 0, 0, 0, 0);
        if (miner == null) miner = new Miner(true, null, 0, 0, null, 0, 0);
        if (autopilot == null) autopilot = new Autopilot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        // These cross-cutting checks only matter when the autopilot is enabled: with it off nothing
        // auto-(re)starts the miner, so a mis-sized gate merely hides consumption at low solar (a UI
        // concern, not stranding).
        if (autopilot.enabled()) {
            // The autopilot's surplus is solar − measured house consumption, and Solar Analytics is
            // the only consumption source (the SG10RS has no whole-home meter). With it disabled the
            // surplus is always unknown, so the autopilot would permanently stop / never start the
            // miner. Require the consumption source when the autopilot is on.
            if (!solarAnalytics.enabled()) {
                throw new IllegalArgumentException("autopilot.enabled requires solar-analytics.enabled:"
                        + " the autopilot needs measured house consumption to compute the surplus;"
                        + " without it the surplus is always unknown and the miner is stranded OFF");
            }
            // The autopilot ladder runs floorW..maxPowerW; the floor can't be below the miner's
            // hardware minimum, and the ceiling must sit strictly above the floor.
            if (autopilot.floorW() < miner.minPowerW()) {
                throw new IllegalArgumentException("autopilot.floor-w (" + autopilot.floorW()
                        + ") must be ≥ miner.min-power-w (" + miner.minPowerW() + ")");
            }
            if (miner.maxPowerW() <= autopilot.floorW()) {
                throw new IllegalArgumentException("miner.max-power-w (" + miner.maxPowerW()
                        + ") must be > autopilot.floor-w (" + autopilot.floorW() + ")");
            }
            // The consumption gate (solar-analytics.min-solar-w) marks consumption unavailable below
            // it (→ surplus unknown → stop). It must sit below the solar level at which the miner
            // could still run, or it would strand a runnable miner OFF. The lower bound on that
            // solar is:
            //   • floorW + headroomW — a running miner needs surplus ≥ floor+headroom, i.e. (base
            //                          load ≥ 0) solar ≥ floor+headroom;
            //   • startSurplusW      — a (re)start needs surplus ≥ this, i.e. solar ≥ startSurplus.
            int gateCeiling = Math.min(autopilot.startSurplusW(), autopilot.floorW() + autopilot.headroomW());
            if (solarAnalytics.minSolarWatts() >= gateCeiling) {
                throw new IllegalArgumentException("solar-analytics.min-solar-w ("
                        + solarAnalytics.minSolarWatts() + ") must be < " + gateCeiling
                        + " (min of autopilot.start-surplus-w and autopilot.floor-w + headroom-w):"
                        + " otherwise the consumption gate would strand the miner OFF while it could"
                        + " still run on solar");
            }
        }
    }

    /** Sungrow WiNet-S dongle — local WebSocket API (wss on 443) for generation. */
    public record Inverter(
            String host,
            int port,
            String wsPath,
            String username,
            String password,
            long pollIntervalMs,
            long requestTimeoutMs) {

        public Inverter {
            if (host == null) host = ""; // no hardcoded IP — supplied via INVERTER_HOST
            if (port == 0) port = 443;
            if (wsPath == null || wsPath.isBlank()) wsPath = "/ws/home/overview";
            if (pollIntervalMs == 0) pollIntervalMs = 10000; // how often to poll the inverter
            if (requestTimeoutMs == 0) requestTimeoutMs = 8000;
        }

        /** Full wss:// endpoint URI string. */
        public String wsUri() {
            return "wss://" + host + ":" + port + wsPath;
        }
    }

    /**
     * Solar Analytics cloud API — measured whole-home consumption (their CT hardware).
     * Authenticates with the account email + password over HTTP Basic; polls
     * {@code /live_site_data} for real-time {@code consumed}/{@code generated} watts.
     */
    public record SolarAnalytics(
            // Master switch — when false the client never polls.
            boolean enabled,
            // API base URL, e.g. https://portal.solaranalytics.com.au/api/v3
            String host,
            // Solar Analytics account email + password (HTTP Basic auth).
            String user,
            String password,
            // Site to read; blank = auto-detect the first active site from /site_list.
            String siteId,
            // How often (ms) to poll live_site_data for whole-home consumption.
            long pollIntervalMs,
            // A reading older than this (seconds) is treated as stale, so the margin
            // becomes unavailable until fresh data returns.
            int staleAfterSeconds,
            // Per-request HTTP timeout (ms).
            long requestTimeoutMs,
            // Only poll the consumption API when the inverter is generating more than
            // this many watts. Below it there can't be a usable surplus (the miner's
            // floor alone exceeds it), so calling the cloud API would be wasteful.
            int minSolarWatts) {

        public SolarAnalytics {
            if (host == null || host.isBlank()) host = "https://portal.solaranalytics.com.au/api/v3";
            if (user == null) user = "";
            if (password == null) password = "";
            if (siteId == null) siteId = "";
            if (pollIntervalMs == 0) pollIntervalMs = 15000;
            if (staleAfterSeconds == 0) staleAfterSeconds = 60;
            if (requestTimeoutMs == 0) requestTimeoutMs = 8000;
            if (minSolarWatts == 0) minSolarWatts = 800;
        }

        /** Usable only when both account email and password are set. */
        public boolean hasCredentials() {
            return !user.isBlank() && !password.isBlank();
        }
    }

    /**
     * Braiins OS+ miner (Antminer S19k Pro) — local GraphQL API at {@code /graphql}
     * on port 80. Used to start/stop mining and set the autotuning power target.
     */
    public record Miner(
            // Master switch — when false the miner integration is inactive.
            boolean enabled,
            // Miner IP on the LAN.
            String host,
            // How often (ms) to poll the miner for status/power.
            long pollIntervalMs,
            // Per-request timeout (ms) for the GraphQL calls.
            long requestTimeoutMs,
            // Optional bearer token, if the miner's API requires authentication.
            String authToken,
            // Hard floor for the autotuning power target (W). The miner cannot run
            // below this, so we never set nor step below it.
            int minPowerW,
            // Hard ceiling for the autotuning power target (W). We never exceed it.
            int maxPowerW) {

        public Miner {
            if (host == null) host = ""; // no hardcoded IP — supplied via MINER_HOST
            if (pollIntervalMs == 0) pollIntervalMs = 10000;
            if (requestTimeoutMs == 0) requestTimeoutMs = 8000;
            if (authToken == null) authToken = "";
            if (minPowerW <= 0) minPowerW = 800;
            if (maxPowerW <= 0) maxPowerW = 3600;
        }

        /** Base URL (scheme + host) for the declarative HTTP client. */
        public String baseUrl() {
            return "http://" + host;
        }

        public boolean hasAuth() {
            return !authToken.isBlank();
        }

        /** Clamp a desired power target to the miner's hard [min, max] limits. */
        public int clampPower(int watts) {
            return Math.max(minPowerW, Math.min(maxPowerW, watts));
        }
    }

    /**
     * Smoothed solar-surplus autopilot (see {@link io.dmitrykislov.miner.autopilot.AutopilotGovernor}).
     * Drives the miner across a fixed power ladder ({@code floorW..maxPowerW} by {@code stepW}) to
     * track the <b>time-averaged</b> solar surplus, so brief clouds are ridden through. The ceiling
     * of the ladder is {@link Miner#maxPowerW()} (not configured here). All powers are watts, all
     * durations milliseconds.
     */
    public record Autopilot(
            // Master switch. Disabled by default — it controls real mining hardware.
            boolean enabled,
            // How often (ms) the control loop evaluates and acts. Much finer than the up/down
            // intervals below (the governor does its own dampening), so emergencies react fast.
            long intervalMs,
            // Lowest power the autopilot runs the miner at (W); below this it stops. Must be ≥
            // miner.min-power-w. Kept above the hardware floor to avoid extreme-minimum operation.
            int floorW,
            // Ladder rung spacing (W) — the granularity of power changes.
            int stepW,
            // Anti-import buffer (W): the target is always ladder-rung ≤ (surplus − headroom), so a
            // running miner never draws more than the surplus.
            int headroomW,
            // Long-window surplus (W) required to (re)start from off. Must be > floorW so start and
            // stop have hysteresis and can't flap.
            int startSurplusW,
            // Cap on how many rungs a single up-move may climb (smooth ramp).
            int upMaxRungsPerCycle,
            // If the miner is over-drawing the surplus by at least this (W), a down-step bypasses
            // the down interval (fast import protection).
            int emergencyGapW,
            // Min time (ms) between up/start commands — the up dampener. Must be ≥ longWindowMs so a
            // just-made change can't contaminate the average that drives the next up-move.
            long upIntervalMs,
            // Min time (ms) between routine down commands (emergency protection bypasses it).
            long downIntervalMs,
            // Short averaging window (ms) — drives down/stop protection (fast reaction).
            long shortWindowMs,
            // Long averaging window (ms) — drives up/start (conservative). Also the "mined long
            // enough" guard before the long average is trusted.
            long longWindowMs,
            // A feed with no sample newer than this (ms) is stale → the surplus is unknown.
            long freshWithinMs,
            // Minimum span (ms) an averaging window must cover to be trusted (else it reports empty:
            // a too-sparse window right after boot/gap must not be acted on).
            long shortCoverageMs,
            long longCoverageMs,
            // Once mining, the miner won't be stopped for this long (ms) unless it is importing hard
            // (over-drawing by ≥ emergencyGapW). Bounds power cycling so a brief dip right after a
            // start doesn't immediately stop the miner. 0 keeps the default; negative disables it.
            long minRunMs) {

        public Autopilot {
            if (minRunMs == 0) minRunMs = 180_000;             // 3 min
            if (minRunMs < 0) minRunMs = 0;                    // explicit negative → disable the guard
            if (intervalMs == 0) intervalMs = 30_000;
            if (floorW == 0) floorW = 1200;
            if (stepW == 0) stepW = 400;
            if (headroomW == 0) headroomW = 200;
            if (startSurplusW == 0) startSurplusW = 1600;
            if (upMaxRungsPerCycle == 0) upMaxRungsPerCycle = 2;
            if (emergencyGapW == 0) emergencyGapW = 800;
            if (upIntervalMs == 0) upIntervalMs = 900_000;   // 15 min
            if (downIntervalMs == 0) downIntervalMs = 300_000; // 5 min
            if (shortWindowMs == 0) shortWindowMs = 180_000;   // 3 min
            if (longWindowMs == 0) longWindowMs = 900_000;     // 15 min
            if (freshWithinMs == 0) freshWithinMs = 90_000;    // 90 s
            if (shortCoverageMs == 0) shortCoverageMs = 60_000; // 60 s
            if (longCoverageMs == 0) longCoverageMs = 300_000;  // 5 min
            // Cadence invariants: a change must not be able to contaminate the average that drives
            // the NEXT change in the same direction, so each interval must be ≥ the window it acts on.
            // Up uses the long window, down uses the short window. (The governor re-checks the up
            // arm, but this is the single place that sees the window sizes for the down arm too.)
            if (upIntervalMs < longWindowMs) {
                throw new IllegalArgumentException("autopilot.up-interval-ms (" + upIntervalMs
                        + ") must be ≥ long-window-ms (" + longWindowMs + "): a just-made up-change"
                        + " would otherwise contaminate the long average driving the next up-move");
            }
            if (downIntervalMs < shortWindowMs) {
                throw new IllegalArgumentException("autopilot.down-interval-ms (" + downIntervalMs
                        + ") must be ≥ short-window-ms (" + shortWindowMs + "): a just-made down-change"
                        + " would otherwise contaminate the short average driving the next down-move");
            }
        }
    }
}
