package io.dmitrykislov.miner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * All configuration for monitoring the house, bound from {@code house.*}.
 *
 * <pre>
 * house
 *  ├─ inverter      → the Sungrow SG10RS / WiNet-S (solar generation)
 *  ├─ power-sensor  → the Powersensor gateway (measured house consumption)
 *  ├─ plug          → the TP-Link Tapo P110 smart plug (switchable load + status)
 *  └─ miner         → the Braiins OS+ miner (Antminer S19k Pro) GraphQL API
 * </pre>
 */
@ConfigurationProperties(prefix = "house")
public record HouseProperties(
        @NestedConfigurationProperty Inverter inverter,
        @NestedConfigurationProperty PowerSensor powerSensor,
        @NestedConfigurationProperty Plug plug,
        @NestedConfigurationProperty Miner miner,
        @NestedConfigurationProperty Autopilot autopilot) {

    public HouseProperties {
        if (inverter == null) inverter = new Inverter(null, 0, null, null, null, 0, 0);
        if (powerSensor == null) powerSensor = new PowerSensor(true, null, 0, 0, 0, 0, null);
        if (plug == null) plug = new Plug(true, null, null, null, null, 0, 0, null, null, null);
        if (miner == null) miner = new Miner(true, null, 0, 0, null, 0, 0);
        if (autopilot == null) autopilot = new Autopilot(false, 0, 0, 0, 0);
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

    /** Powersensor gateway — local UDP pub/sub API for measured whole-home power. */
    public record PowerSensor(
            // Master switch — when false the client never opens a socket.
            boolean enabled,
            // Gateway/plug IP that serves the local API.
            String host,
            // UDP port of the local API (proprietary high port).
            int port,
            // Lifetime (seconds) passed in the subscribe() request.
            int subscribeLifetimeSeconds,
            // How often (seconds) to re-send subscribe() to keep the stream alive;
            // must be < the lifetime or the device stops streaming.
            int resubscribeIntervalSeconds,
            // A measured reading older than this (seconds) is treated as stale, so
            // the margin becomes unavailable until fresh data returns.
            int staleAfterSeconds,
            // Optional explicit MAC of the mains clamp (whole-home). Blank = auto-detect
            // the clamp as the reporting device whose voltage is null.
            String clampMac) {

        public PowerSensor {
            if (host == null) host = ""; // no hardcoded IP — supplied via POWERSENSOR_HOST
            if (port == 0) port = 49476;
            if (subscribeLifetimeSeconds == 0) subscribeLifetimeSeconds = 180;
            if (resubscribeIntervalSeconds == 0) resubscribeIntervalSeconds = 90;
            if (staleAfterSeconds == 0) staleAfterSeconds = 30;
            if (clampMac == null) clampMac = "";
        }

        /** True when {@code mac} is the whole-home clamp, per config or voltage heuristic. */
        public boolean isClamp(String mac, Double voltage) {
            if (!clampMac.isBlank()) {
                return clampMac.equalsIgnoreCase(mac);
            }
            return voltage == null; // the mains clamp reports no voltage
        }
    }

    /**
     * TP-Link Tapo P110 smart plug — local encrypted HTTP API (KLAP) on port 80.
     * Authenticates with the TP-Link/Tapo <em>account</em> credentials (there is no
     * device-local password); they are hashed locally during the KLAP handshake.
     */
    public record Plug(
            // Master switch — when false the plug integration is inactive.
            boolean enabled,
            // Plug IP on the LAN.
            String host,
            // TP-Link/Tapo account email (KLAP username).
            String email,
            // TP-Link/Tapo account password.
            String password,
            // Friendly name shown in the UI (falls back to the device's own nickname).
            String name,
            // How often (ms) to poll the plug for on/off + energy status.
            long pollIntervalMs,
            // Per-request timeout (ms) for the HTTP calls.
            long requestTimeoutMs,
            // Transport: "cloud" (TP-Link cloud relay) or "local" (on-LAN KLAP).
            // This P110 runs TPAP locally (unsupported), so cloud is the default.
            String mode,
            // Device MAC (no separators) used to match the plug in the cloud device
            // list; blank = match the first P110 by model.
            String mac,
            // TP-Link cloud base URL for login/device-list.
            String cloudBaseUrl) {

        public Plug {
            if (host == null) host = ""; // no hardcoded IP — supplied via PLUG_HOST
            if (email == null) email = "";
            if (password == null) password = "";
            if (name == null) name = "";
            if (pollIntervalMs == 0) pollIntervalMs = 10000;
            if (requestTimeoutMs == 0) requestTimeoutMs = 8000;
            if (mode == null || mode.isBlank()) mode = "cloud";
            if (mac == null) mac = "";
            if (cloudBaseUrl == null || cloudBaseUrl.isBlank()) cloudBaseUrl = "https://wap.tplinkcloud.com";
        }

        /** Base URL of the plug's local API (KLAP transport). */
        public String baseUrl() {
            return "http://" + host + "/app";
        }

        /** Normalised MAC (uppercase, no separators) for cloud matching. */
        public String normalisedMac() {
            return mac.replace("-", "").replace(":", "").toUpperCase();
        }

        public boolean isCloud() {
            return "cloud".equalsIgnoreCase(mode);
        }

        /** Credentials are only usable if both email and password are set. */
        public boolean hasCredentials() {
            return !email.isBlank() && !password.isBlank();
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
     * Solar-margin autopilot: automatically starts/stops the miner and steps its
     * power target up/down to soak up surplus solar. All thresholds are in watts.
     */
    public record Autopilot(
            // Master switch. Disabled by default — it controls real mining hardware.
            boolean enabled,
            // How often (ms) to evaluate the margin and act.
            long intervalMs,
            // Start the miner (and step it up) when margin ≥ this (W).
            int startMarginW,
            // Back the miner off (step down / stop) when margin < this (W).
            int lowMarginW,
            // Power increment/decrement per step (W).
            int stepW) {

        public Autopilot {
            if (intervalMs == 0) intervalMs = 30000;
            if (startMarginW == 0) startMarginW = 1000;
            if (lowMarginW == 0) lowMarginW = 100;
            if (stepW == 0) stepW = 1000;
        }
    }
}
