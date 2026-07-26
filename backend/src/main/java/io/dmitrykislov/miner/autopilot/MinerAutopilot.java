package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.OptionalDouble;

/**
 * Solar-margin autopilot. Every {@code house.autopilot.interval-ms} (default 30 s)
 * it reads a <b>fresh</b> miner state and the current power margin, asks the pure
 * {@link MinerAutopilotPlanner} what to do, and applies it via {@link MinerService}.
 * Disabled by default (it drives real mining hardware).
 *
 * <p>State handling is deliberately conservative:
 * <ul>
 *   <li>every tick starts with a <b>live</b> {@link MinerService#refresh()} — never a
 *       cached status — so decisions use the miner's actual on/off state and power target;</li>
 *   <li>each mutating operation <b>re-verifies</b> the miner state immediately before it
 *       runs (start only if still off, step only if still mining, stop only if still
 *       running), so a state change between decision and action can't cause a wrong op.</li>
 * </ul>
 */
@Service
public class MinerAutopilot {

    private static final Logger log = LoggerFactory.getLogger(MinerAutopilot.class);

    private final MarginSource marginSource;
    private final MinerService minerService;
    private final HouseProperties.Autopilot cfg;
    private final HouseProperties.Miner minerCfg;
    private final MinerAutopilotPlanner planner;

    public MinerAutopilot(MarginSource marginSource, MinerService minerService, HouseProperties props) {
        this.marginSource = marginSource;
        this.minerService = minerService;
        this.cfg = props.autopilot();
        this.minerCfg = props.miner();
        this.planner = new MinerAutopilotPlanner(
                minerCfg.minPowerW(), minerCfg.maxPowerW(),
                cfg.startMarginW(), cfg.lowMarginW(), cfg.stepW());
        if (cfg.enabled()
                && !MinerAutopilotPlanner.isStableConfig(cfg.startMarginW(), cfg.lowMarginW(), cfg.stepW())) {
            log.warn("Autopilot thresholds may oscillate: deadzone {}W < step {}W. "
                            + "Set start-margin ≥ low-margin + step (e.g. {}W) to stabilise.",
                    cfg.startMarginW() - cfg.lowMarginW(), cfg.stepW(), cfg.lowMarginW() + cfg.stepW());
        }
    }

    @Scheduled(fixedDelayString = "${house.autopilot.interval-ms:30000}",
               initialDelayString = "${house.autopilot.interval-ms:30000}")
    public void tick() {
        if (!cfg.enabled()) return;

        // Always start from a fresh, live miner state.
        MinerStatus st = minerService.refresh();
        if (st == null || !st.reachable()) {
            log.debug("autopilot: miner unreachable — skipping");
            return;
        }

        OptionalDouble margin = marginSource.currentMarginWatts();
        if (margin.isEmpty()) {
            // The margin is unknowable: either solar is unavailable (inverter offline →
            // treat as no generation) or house consumption is unavailable (Solar Analytics
            // stale/offline → draw could be anything). It is unsafe to keep mining on a
            // guess, so stop the miner if it is running.
            if (st.running()) {
                log.info("autopilot: margin unknown (solar or consumption unavailable) — stopping miner for safety");
                stopIfRunning();
            } else {
                log.debug("autopilot: margin unknown and miner already off — nothing to do");
            }
            return;
        }
        // While SUSPENDED the service is up but draws ~0 W, so its draw is NOT reflected in
        // the margin — the planner's "margin already includes the miner" assumption breaks
        // and it would ramp on phantom surplus. Skip: autopilot can't fix a suspension anyway.
        if (MinerStatus.SUSPENDED.equals(st.state())) {
            log.debug("autopilot: miner suspended (draw not in margin) — skipping");
            return;
        }

        boolean mining = MinerStatus.MINING.equals(st.state());
        int current = st.powerTargetW() != null ? st.powerTargetW() : minerCfg.minPowerW();
        AutopilotDecision d = planner.decide(margin.getAsDouble(), mining, current);
        log.info("autopilot: {}", d.reason());
        apply(d);
    }

    private void apply(AutopilotDecision d) {
        switch (d.action()) {
            case START -> startAtMin(d.targetPowerW());
            case STEP_UP, STEP_DOWN -> setPowerIfMining(d.targetPowerW());
            case STOP -> stopIfRunning();
            case NONE -> { /* hold */ }
        }
    }

    // ---- operations, each re-verifying live state immediately before acting ----

    private void startAtMin(int target) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (now.running()) {
            log.info("autopilot: miner already running — skipping start");
            return;
        }
        minerService.setPowerTarget(target, true); // start at the min power target
        minerService.start();
    }

    private void setPowerIfMining(int target) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!MinerStatus.MINING.equals(now.state())) {
            log.info("autopilot: miner not mining ({}) — skipping power change", now.state());
            return;
        }
        minerService.setPowerTarget(target, true);
    }

    private void stopIfRunning() {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!now.running()) {
            log.info("autopilot: miner already off — skipping stop");
            return;
        }
        minerService.stop();
    }
}
