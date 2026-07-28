package io.dmitrykislov.miner.braiins;

import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.util.Rounding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls the Braiins miner for status + configured power target, publishes each
 * snapshot to SSE subscribers, and performs start/stop/set-power commands
 * (refreshing status immediately after).
 */
@Service
public class MinerService {

    private static final Logger log = LoggerFactory.getLogger(MinerService.class);

    private final BraiinsMinerClient client;
    private final MinerStreamService stream;
    private final HouseProperties.Miner cfg;

    public MinerService(BraiinsMinerClient client, MinerStreamService stream, HouseProperties props) {
        this.client = client;
        this.stream = stream;
        this.cfg = props.miner();
    }

    @Scheduled(fixedDelayString = "${house.miner.poll-interval-ms:10000}", initialDelayString = "2500")
    public void poll() {
        if (cfg.enabled()) refresh();
    }

    public MinerStatus refresh() {
        Instant now = Instant.now();
        if (!cfg.enabled() || cfg.host().isBlank()) {
            return publish(MinerStatus.offline(now, "miner integration disabled or no host configured"));
        }
        try {
            JsonNode bm = client.status();
            String model = bm.path("info").path("modelName").asText(null);
            JsonNode uptime = bm.path("uptime");
            boolean running = !uptime.isMissingNode() && !uptime.isNull();
            Long uptimeS = running ? uptime.path("durationS").asLong() : null;

            JsonNode tuning = bm.path("config").path("autotuning");
            Integer powerTarget = tuning.hasNonNull("powerTarget") ? tuning.path("powerTarget").asInt() : null;
            boolean tunerEnabled = tuning.path("enabled").asBoolean(false);

            // Pool health — the reason bosminer pauses ("dead pools") is no alive pool.
            int totalPools = 0, activePools = 0;
            for (JsonNode group : bm.path("info").path("poolGroups")) {
                for (JsonNode pool : group.path("pools")) {
                    totalPools++;
                    if (pool.path("active").asBoolean(false)) activePools++;
                }
            }

            Double hashrateThs = null;
            Integer powerDrawW = null;
            List<Fan> fans = new ArrayList<>();
            if (running) {
                try {
                    JsonNode info = client.realtime();
                    JsonNode s = info.path("summary");
                    double mhs5s = s.path("realHashrate").path("mhs5S").asDouble(0);
                    hashrateThs = Rounding.toPlaces(mhs5s / 1_000_000.0, 2); // MH/s → TH/s
                    if (s.path("power").hasNonNull("approxConsumptionW")) {
                        powerDrawW = s.path("power").path("approxConsumptionW").asInt();
                    }
                    for (JsonNode f : info.path("fans")) {
                        fans.add(new Fan(f.path("name").asText(""),
                                f.path("rpm").asInt(0), f.path("speed").asInt(0)));
                    }
                } catch (Exception e) {
                    log.debug("realtime stats unavailable (likely suspended): {}", e.toString());
                }
            }

            // Derive the real state. "running" = service up; it can still be SUSPENDED.
            String state, reason = null;
            boolean mining = hashrateThs != null && hashrateThs > 0;
            if (!running) {
                state = MinerStatus.STOPPED;
                reason = "BOSMiner service is stopped";
            } else if (mining) {
                state = MinerStatus.MINING;
            } else {
                state = MinerStatus.SUSPENDED;
                if (totalPools == 0) {
                    reason = "Suspended: no pool configured — add a reachable pool to mine";
                } else if (activePools == 0) {
                    reason = "Suspended: no active pool (pools unreachable / dead pools)";
                } else {
                    reason = "Suspended: warming up / paused";
                }
            }

            return publish(new MinerStatus(true, running, state, reason, model, powerTarget, tunerEnabled,
                    activePools, totalPools, hashrateThs, powerDrawW, fans, uptimeS, now, null));
        } catch (Exception e) {
            log.warn("Miner poll failed: {}", e.toString());
            return publish(MinerStatus.offline(now, e.getMessage()));
        }
    }

    public MinerStatus start() {
        try {
            client.start();
        } catch (Exception e) {
            log.warn("Miner start failed: {}", e.toString());
            return publish(MinerStatus.offline(Instant.now(), e.getMessage()));
        }
        return refresh();
    }

    public MinerStatus stop() {
        try {
            client.stop();
        } catch (Exception e) {
            log.warn("Miner stop failed: {}", e.toString());
            return publish(MinerStatus.offline(Instant.now(), e.getMessage()));
        }
        return refresh();
    }

    public MinerStatus setPowerTarget(int watts, boolean apply) {
        // Never send an out-of-range target to the hardware, whatever the caller asks.
        int clamped = cfg.clampPower(watts);
        if (clamped != watts) {
            log.info("Clamped power target {}W to hardware limits [{},{}] → {}W",
                    watts, cfg.minPowerW(), cfg.maxPowerW(), clamped);
        }
        try {
            client.setPowerTarget(clamped, apply);
        } catch (Exception e) {
            log.warn("Miner setPowerTarget({}) failed: {}", clamped, e.toString());
            return publish(MinerStatus.offline(Instant.now(), e.getMessage()));
        }
        // Read back and verify the miner accepted the new target. This checks the configured
        // SETPOINT, not the live draw — Braiins autotuning ramps the actual consumption toward the
        // target separately (and slowly), so a draw below target right after a change is normal. The
        // read-back distinguishes an accepted command from one that silently didn't apply.
        MinerStatus after = refresh();
        Integer applied = after.powerTargetW();
        if (applied != null && applied == clamped) {
            log.info("Power target set to {}W — miner confirms {}W (actual draw ramps via autotuning)",
                    clamped, applied);
        } else {
            log.warn("Power target set to {}W but miner reports {}W — command may not have applied",
                    clamped, applied);
        }
        return after;
    }

    private MinerStatus publish(MinerStatus s) {
        stream.publish(s);
        return s;
    }
}
