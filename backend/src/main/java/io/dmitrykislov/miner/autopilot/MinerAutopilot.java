package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Solar-margin autopilot. Every {@code house.autopilot.interval-ms} (default 30 s)
 * it reads a <b>fresh</b> miner state and the current power margin, asks the pure
 * {@link MinerAutopilotPlanner} what to do, and applies it via {@link MinerService}.
 * Off by default (it drives real mining hardware); can be toggled at runtime via the UI.
 *
 * <p>State handling is deliberately conservative:
 * <ul>
 *   <li>every tick starts with a <b>live</b> {@link MinerService#refresh()} — never a
 *       cached status — so decisions use the miner's actual on/off state and power target;</li>
 *   <li>each mutating operation <b>re-verifies</b> the miner state immediately before it
 *       runs (start only if still off, step only if still mining, stop only if still
 *       running), so a state change between decision and action can't cause a wrong op.</li>
 * </ul>
 *
 * <p>It also tracks a UI-facing {@link AutopilotStatus}: the last decision and the details
 * of the last change it actually made, published to {@link AutopilotStreamService}.
 */
@Service
public class MinerAutopilot {

    private static final Logger log = LoggerFactory.getLogger(MinerAutopilot.class);

    private final MarginSource marginSource;
    private final MinerService minerService;
    private final HouseProperties.Autopilot cfg;
    private final HouseProperties.Miner minerCfg;
    private final MinerAutopilotPlanner planner;
    private final AutopilotStreamService statusStream;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<AutopilotStatus.Change> lastChange = new AtomicReference<>();
    private volatile Instant evaluatedAt;
    private volatile String lastDecision;

    public MinerAutopilot(MarginSource marginSource, MinerService minerService,
                          HouseProperties props, AutopilotStreamService statusStream) {
        this.marginSource = marginSource;
        this.minerService = minerService;
        this.cfg = props.autopilot();
        this.minerCfg = props.miner();
        this.statusStream = statusStream;
        this.planner = new MinerAutopilotPlanner(
                minerCfg.minPowerW(), minerCfg.maxPowerW(),
                cfg.startMarginW(), cfg.lowMarginW(), cfg.stepW());
        this.enabled.set(cfg.enabled());
        this.lastDecision = cfg.enabled() ? "enabled — awaiting first evaluation" : "disabled";
        if (cfg.enabled()
                && !MinerAutopilotPlanner.isStableConfig(cfg.startMarginW(), cfg.lowMarginW(), cfg.stepW())) {
            log.warn("Autopilot thresholds may oscillate: deadzone {}W < step {}W. "
                            + "Set start-margin ≥ low-margin + step (e.g. {}W) to stabilise.",
                    cfg.startMarginW() - cfg.lowMarginW(), cfg.stepW(), cfg.lowMarginW() + cfg.stepW());
        }
        statusStream.publish(status());
    }

    // ---- runtime control / status (used by the API + UI) --------------------

    public boolean isEnabled() {
        return enabled.get();
    }

    /** Turn the control loop on/off at runtime; publishes the new status. */
    public void setEnabled(boolean on) {
        boolean was = enabled.getAndSet(on);
        if (was != on) {
            log.info("autopilot {} via API", on ? "ENABLED" : "DISABLED");
            lastDecision = on ? "enabled — awaiting next evaluation" : "disabled";
        }
        statusStream.publish(status());
    }

    public AutopilotStatus status() {
        AutopilotStatus.Change c = lastChange.get();
        return new AutopilotStatus(enabled.get(), evaluatedAt, lastDecision,
                c != null ? c.at() : null, c);
    }

    @Scheduled(fixedDelayString = "${house.autopilot.interval-ms:30000}",
               initialDelayString = "${house.autopilot.interval-ms:30000}")
    public void tick() {
        if (!enabled.get()) return;
        try {
            evaluate();
        } finally {
            evaluatedAt = Instant.now();
            statusStream.publish(status());
        }
    }

    private void evaluate() {
        // Always start from a fresh, live miner state.
        MinerStatus st = minerService.refresh();
        if (st == null || !st.reachable()) {
            lastDecision = "miner unreachable — skipping";
            log.debug("autopilot: {}", lastDecision);
            return;
        }

        OptionalDouble margin = marginSource.currentMarginWatts();
        if (margin.isEmpty()) {
            // The margin is unknowable: either solar is unavailable (inverter offline →
            // treat as no generation) or house consumption is unavailable (Solar Analytics
            // stale/offline → draw could be anything). It is unsafe to keep mining on a
            // guess, so stop the miner if it is running.
            if (st.running()) {
                lastDecision = "margin unknown (solar or consumption unavailable) — stopping miner for safety";
                log.info("autopilot: {}", lastDecision);
                stopIfRunning(lastDecision);
            } else {
                lastDecision = "margin unknown and miner already off — nothing to do";
                log.debug("autopilot: {}", lastDecision);
            }
            return;
        }
        // While SUSPENDED the service is up but draws ~0 W, so its draw is NOT reflected in
        // the margin — the planner's "margin already includes the miner" assumption breaks
        // and it would ramp on phantom surplus. Skip: autopilot can't fix a suspension anyway.
        if (MinerStatus.SUSPENDED.equals(st.state())) {
            lastDecision = "miner suspended (draw not in margin) — skipping";
            log.debug("autopilot: {}", lastDecision);
            return;
        }

        boolean mining = MinerStatus.MINING.equals(st.state());
        int current = st.powerTargetW() != null ? st.powerTargetW() : minerCfg.minPowerW();
        AutopilotDecision d = planner.decide(margin.getAsDouble(), mining, current);
        lastDecision = d.reason();
        log.info("autopilot: {}", d.reason());
        apply(d);
    }

    private void apply(AutopilotDecision d) {
        switch (d.action()) {
            case START -> startAtMin(d.targetPowerW(), d.reason());
            case STEP_UP, STEP_DOWN -> setPowerIfMining(d.action().name(), d.targetPowerW(), d.reason());
            case STOP -> stopIfRunning(d.reason());
            case NONE -> { /* hold */ }
        }
    }

    // ---- operations, each re-verifying live state immediately before acting ----

    private void startAtMin(int target, String reason) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (now.running()) {
            log.info("autopilot: miner already running — skipping start");
            return;
        }
        minerService.setPowerTarget(target, true); // start at the min power target
        minerService.start();
        recordChange("START", null, target, reason);
    }

    private void setPowerIfMining(String action, int target, String reason) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!MinerStatus.MINING.equals(now.state())) {
            log.info("autopilot: miner not mining ({}) — skipping power change", now.state());
            return;
        }
        Integer from = now.powerTargetW();
        minerService.setPowerTarget(target, true);
        recordChange(action, from, target, reason);
    }

    private void stopIfRunning(String reason) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!now.running()) {
            log.info("autopilot: miner already off — skipping stop");
            return;
        }
        Integer from = now.powerTargetW();
        minerService.stop();
        recordChange("STOP", from, null, reason);
    }

    private void recordChange(String action, Integer fromW, Integer toW, String detail) {
        lastChange.set(new AutopilotStatus.Change(Instant.now(), action, fromW, toW, detail));
    }
}
