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

        OptionalDouble margin = marginSource.currentMarginWatts();
        if (margin.isEmpty()) {
            log.debug("autopilot: margin unknown (inverter offline) — skipping");
            return;
        }
        MinerStatus st = minerStream.latest();
        if (st == null || !st.reachable()) {
            log.debug("autopilot: miner status unavailable — skipping");
            return;
        }

        int current = st.powerTargetW() != null ? st.powerTargetW() : minerCfg.minPowerW();
        AutopilotDecision d = planner.decide(margin.getAsDouble(), st.running(), current);
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
