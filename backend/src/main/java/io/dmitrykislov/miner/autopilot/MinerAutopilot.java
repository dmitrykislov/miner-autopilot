package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.braiins.MinerStreamService;
import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.OptionalDouble;

/**
 * Solar-margin autopilot. Every {@code house.autopilot.interval-ms} (default 30 s)
 * it reads the current power margin and the miner state, asks the pure
 * {@link MinerAutopilotPlanner} what to do, and applies it via {@link MinerService}.
 * Disabled by default (it drives real mining hardware).
 */
@Service
public class MinerAutopilot {

    private static final Logger log = LoggerFactory.getLogger(MinerAutopilot.class);

    private final MarginSource marginSource;
    private final MinerService minerService;
    private final MinerStreamService minerStream;
    private final HouseProperties.Autopilot cfg;
    private final HouseProperties.Miner minerCfg;
    private final MinerAutopilotPlanner planner;

    public MinerAutopilot(MarginSource marginSource, MinerService minerService,
                          MinerStreamService minerStream, HouseProperties props) {
        this.marginSource = marginSource;
        this.minerService = minerService;
        this.minerStream = minerStream;
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

        MinerStatus st = minerStream.latest();
        if (st == null || !st.reachable()) {
            log.debug("autopilot: miner status unavailable — skipping");
            return;
        }

        OptionalDouble margin = marginSource.currentMarginWatts();
        if (margin.isEmpty()) {
            // The margin is unknowable: either solar is unavailable (inverter
            // offline → treat as no generation) or house consumption is unavailable
            // (Powersensor meter offline → draw could be anything). Either way it is
            // unsafe to keep mining on a guess, so stop the miner if it is running.
            if (st.running()) {
                log.info("autopilot: margin unknown (solar or house meter unavailable) — stopping miner for safety");
                minerService.stop();
            } else {
                log.debug("autopilot: margin unknown and miner already off — nothing to do");
            }
            return;
        }
        // While SUSPENDED the service is up but draws ~0 W, so its draw is NOT
        // reflected in the margin — the planner's "margin already includes the
        // miner" assumption breaks and it would ramp on phantom surplus. Skip:
        // autopilot can't resolve a suspension (e.g. dead pools) anyway.
        if (MinerStatus.SUSPENDED.equals(st.state())) {
            log.debug("autopilot: miner suspended (draw not reflected in margin) — skipping");
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
            case START -> {
                minerService.setPowerTarget(d.targetPowerW(), true); // start at min power
                minerService.start();
            }
            case STEP_UP, STEP_DOWN -> minerService.setPowerTarget(d.targetPowerW(), true);
            case STOP -> minerService.stop();
            case NONE -> { /* hold */ }
        }
    }
}
