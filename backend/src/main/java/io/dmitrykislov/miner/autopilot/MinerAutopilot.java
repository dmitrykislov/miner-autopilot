package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.braiins.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.history.PowerChangeEvent;
import io.dmitrykislov.miner.history.TelemetryStore;
import io.dmitrykislov.miner.inverter.InverterStreamService;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smoothed solar-surplus autopilot. Every {@code house.autopilot.interval-ms} (default 30 s) it
 * reads a <b>fresh</b> miner state and the time-averaged surplus signals, asks the pure
 * {@link AutopilotGovernor} what to do, and applies it via {@link MinerService}. Off by default
 * (it drives real mining hardware); can be toggled at runtime via the UI.
 *
 * <p>The averaging engine ({@link EnergyAverages}, fed by {@link EnergySampler}) rides through brief
 * clouds; the governor quantizes power onto a ladder, dampens ramp-up, and reacts fast on drops. See
 * {@link AutopilotGovernor} for the decision model.
 *
 * <p>State handling is deliberately conservative:
 * <ul>
 *   <li>every tick starts with a <b>live</b> {@link MinerService#refresh()} — never a cached
 *       status — so decisions use the miner's actual on/off state and power target;</li>
 *   <li>the surplus is trusted only when the live feed is valid <em>now</em> (inverter online,
 *       consumption metered, snapshot fresh) <b>and</b> the rolling windows are fresh — otherwise
 *       the governor is told {@code dataFresh = false} and stops a running miner for safety;</li>
 *   <li>each mutating operation <b>re-verifies</b> the miner state immediately before it runs
 *       (start only if still off, step only if still mining, stop only if still running), so a
 *       state change between decision and action can't cause a wrong op.</li>
 * </ul>
 */
@Service
public class MinerAutopilot {

    private static final Logger log = LoggerFactory.getLogger(MinerAutopilot.class);

    private final EnergyAverages energy;
    private final InverterStreamService inverter;
    private final MinerService minerService;
    private final HouseProperties.Miner minerCfg;
    private final AutopilotGovernor governor;
    private final AutopilotStreamService statusStream;
    private final Duration maxSnapshotAge;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<AutopilotStatus.Change> lastChange = new AtomicReference<>();
    private volatile Instant evaluatedAt;
    private volatile String lastDecision;
    // When the miner began continuously mining (null when not mining). Drives the governor's
    // "mined long enough for a valid up-average" guard; seeded from the miner's uptime on the FIRST
    // observation (so a long-running miner can ramp soon after a controller restart), but reset to
    // "now" on an observed resume from a non-mining state — see trackMiningSince.
    private volatile Instant miningSince;
    // False until the first tick has observed the miner. Lets us tell a first observation (trust the
    // miner's uptime) from an observed resume (mining truly just (re)started). A monotonic boolean —
    // not the last state — so a one-off null/garbled state can't be mistaken for "never observed".
    private volatile boolean minerObserved;

    public MinerAutopilot(EnergyAverages energy, InverterStreamService inverter,
                          MinerService minerService, HouseProperties props,
                          AutopilotStreamService statusStream, TelemetryStore history) {
        this.energy = energy;
        this.inverter = inverter;
        this.minerService = minerService;
        this.minerCfg = props.miner();
        this.statusStream = statusStream;
        HouseProperties.Autopilot cfg = props.autopilot();
        this.governor = new AutopilotGovernor(new AutopilotGovernor.Config(
                cfg.floorW(), minerCfg.maxPowerW(), cfg.stepW(), cfg.headroomW(), cfg.startSurplusW(),
                Duration.ofMillis(cfg.upIntervalMs()), Duration.ofMillis(cfg.downIntervalMs()),
                Duration.ofMillis(cfg.longWindowMs()), cfg.upMaxRungsPerCycle(), cfg.emergencyGapW(),
                Duration.ofMillis(cfg.minRunMs())));
        // Tolerate a few missed/slow inverter polls, but treat a longer gap as "no longer known":
        // a stalled poller keeps handing back its last snapshot, so without this the surplus could
        // be piloted on stale data. 4× the poll interval rides out transient GC/scheduling jitter.
        this.maxSnapshotAge = Duration.ofMillis(Math.max(1L, props.inverter().pollIntervalMs()) * 4);
        this.enabled.set(cfg.enabled());
        // Restore the last power change from persisted history so a controller restart doesn't forget
        // what it just did: the governor's restart cooldown / up-dampening are measured from
        // lastChangeAt, so without this a reboot would reset them to "elapsed" and could act too soon.
        restoreLastChange(history);
        this.lastDecision = cfg.enabled() ? "enabled — awaiting first evaluation" : "disabled";
        statusStream.publish(status());
    }

    private void restoreLastChange(TelemetryStore history) {
        try {
            PowerChangeEvent e = history.latestEvent();
            if (e != null) {
                lastChange.set(new AutopilotStatus.Change(e.at(), e.action(), e.fromW(), e.toW(), e.reason()));
                log.info("autopilot: restored last change from history — {} at {}", e.action(), e.at());
            }
        } catch (Exception ex) {
            log.debug("autopilot: could not restore last change from history: {}", ex.toString());
        }
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
        Instant now = Instant.now();
        // Always start from a fresh, live miner state.
        MinerStatus st = minerService.refresh();
        if (st == null) {
            lastDecision = "miner status unavailable — skipping";
            log.debug("autopilot: {}", lastDecision);
            return;
        }
        // NOTE: we deliberately do NOT bail when the miner is unreachable. A Braiins miner whose
        // BOSMiner service is stopped reports its GraphQL as "unavailable" (→ unreachable), yet it
        // can still be (re)started. The governor treats an unreachable miner as OFF and start-eligible,
        // so it can recover the miner it previously stopped once the surplus returns.

        trackMiningSince(st, now);

        // The surplus is trustworthy only if the live feed is valid right now AND the rolling
        // windows are fresh. feedValid catches an offline/unmetered/stalled inverter immediately
        // (a stronger, instantaneous signal than window staleness); energy.dataFresh catches a
        // dead sampler. Either failing → the governor treats the surplus as unknown.
        boolean dataFresh = feedValid(now) && energy.dataFresh(now);
        EnergyAverages.Signals sig = energy.signals(now);

        AutopilotGovernor.Input input = new AutopilotGovernor.Input(
                now, st.reachable(), st.running(), MinerStatus.SUSPENDED.equals(st.state()),
                st.powerTargetW(), miningSince, lastChangeAt(), dataFresh,
                sig.shortSurplusW(), sig.longSurplusW());

        AutopilotDecision d = governor.decide(input);
        lastDecision = d.reason();
        log.info("autopilot: {}", d.reason());
        apply(d);
    }

    /** True when the latest inverter snapshot is online, consumption-metered, and fresh. */
    private boolean feedValid(Instant now) {
        InverterSnapshot snap = inverter.latest();
        return snap != null && snap.online()
                && snap.powerBalance() != null && snap.powerBalance().consumptionMetered()
                && snap.timestamp() != null
                && Duration.between(snap.timestamp(), now).compareTo(maxSnapshotAge) <= 0;
    }

    private void trackMiningSince(MinerStatus st, Instant now) {
        if (MinerStatus.MINING.equals(st.state())) {
            if (miningSince == null) {
                if (minerObserved) {
                    // We observed a non-mining state before and now see mining → it truly just
                    // (re)started (e.g. resume from SUSPENDED). Start the clock now so the up-average
                    // guard makes the long window refill with real mining samples before any ramp —
                    // the window was contaminated while the miner drew ~0 W.
                    miningSince = now;
                } else {
                    // First observation after a controller restart: trust the miner's own uptime, so a
                    // long-running miner isn't made to wait a whole long-window before it can ramp.
                    Long up = st.uptimeSeconds();
                    miningSince = (up != null && up > 0) ? now.minusSeconds(up) : now;
                }
            }
        } else {
            miningSince = null; // suspended/stopped/offline breaks continuous mining
        }
        minerObserved = true;
    }

    private Instant lastChangeAt() {
        AutopilotStatus.Change c = lastChange.get();
        return c != null ? c.at() : null;
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
        // Re-verify to avoid double-starting: skip only if it is genuinely up (reachable AND running).
        // We deliberately do NOT bail when unreachable — a stopped Braiins miner reports unreachable
        // but the start command still brings it up. setPowerTarget/start each fail safely (logged, no
        // throw) if the miner is truly gone; if the target couldn't be set while stopped, the miner
        // comes up at its previous target and the next tick's fast down-check corrects it.
        if (now != null && now.reachable() && now.running()) {
            log.info("autopilot: miner already running — skipping start");
            return;
        }
        minerService.setPowerTarget(target, true); // aim for the floor power target
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
